package com.nocook.service.application;

import com.nocook.domain.effect.Fact;
import com.nocook.domain.event.CaseEvent;
import com.nocook.domain.model.AckChannel;
import com.nocook.domain.model.AckChoice;
import com.nocook.domain.model.AssuranceCase;
import com.nocook.domain.model.CaseState;
import com.nocook.domain.model.CloseReason;
import com.nocook.domain.model.Outcome;
import com.nocook.domain.model.Timings;
import com.nocook.domain.oms.OmsView;
import com.nocook.domain.policy.CasePolicy;
import com.nocook.domain.policy.TransitionResult;
import com.nocook.service.config.TraceIds;
import com.nocook.service.observability.NocookMeters;
import com.nocook.service.persistence.CaseEventRepository;
import com.nocook.service.persistence.CaseRepository;
import com.nocook.service.persistence.CaseRepository.AckUpdate;
import com.nocook.service.persistence.CaseRepository.CaseRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DOMAIN.md section 3.2 in one transaction: lock the case, run the MerchantAck transition (no OMS read), persist,
 * apply effects. Used by the tablet endpoint and by the DTMF webhook.
 */
@Service
public class AckService {

    public sealed interface AckResult {
        record Acked(UUID caseId, AckChoice choice, AckChannel channel, Instant ackedAt, boolean duplicate)
                implements AckResult {}

        /** code = ALREADY_ACKNOWLEDGED | CASE_CLOSED | NOT_ACTIONABLE */
        record Rejected(
                String code,
                UUID caseId,
                CaseState state,
                @Nullable CloseReason closeReason,
                @Nullable AckChoice ackChoice,
                @Nullable Instant ackedAt) implements AckResult {}

        record NotFound(UUID caseId) implements AckResult {}

        record OutletMismatch(UUID caseId) implements AckResult {}
    }

    private final CaseRepository cases;
    private final CaseEventRepository caseEvents;
    private final EffectApplier effectApplier;
    private final ObjectProvider<OutboxWaker> outboxWaker;
    private final NocookMeters meters;
    private final TraceIds traceIds;
    private final Clock clock;
    private final Timings timings;

    public AckService(
            CaseRepository cases,
            CaseEventRepository caseEvents,
            EffectApplier effectApplier,
            ObjectProvider<OutboxWaker> outboxWaker,
            NocookMeters meters,
            TraceIds traceIds,
            Clock clock,
            Timings timings) {
        this.cases = cases;
        this.caseEvents = caseEvents;
        this.effectApplier = effectApplier;
        this.outboxWaker = outboxWaker;
        this.meters = meters;
        this.traceIds = traceIds;
        this.clock = clock;
        this.timings = timings;
    }

    /**
     * @param outletId when non-null (tablet calls) the case must belong to this outlet; null skips the check (IVR)
     */
    @Transactional
    public AckResult ack(
            UUID caseId, @Nullable String outletId, CaseEvent.MerchantAck ack, @Nullable Integer caseVersionSeen) {
        Optional<CaseRecord> locked = cases.lockById(caseId);
        if (locked.isEmpty()) {
            return new AckResult.NotFound(caseId);
        }
        CaseRecord record = locked.get();
        AssuranceCase current = record.assuranceCase();
        if (outletId != null && !outletId.equals(current.outletId())) {
            return new AckResult.OutletMismatch(caseId);
        }
        Instant now = clock.instant();
        String traceId = traceIds.current();
        TransitionResult result = CasePolicy.transition(current, ack, new OmsView.NotRead(), now, timings);
        String deviceId = ack.channel() == AckChannel.TABLET ? ack.actor() : null;
        for (Fact fact : result.facts()) {
            caseEvents.insert(caseId, withVersionSeen(fact, caseVersionSeen), now, ack.channel().name(), deviceId, null, traceId);
        }
        return switch (result.outcome()) {
            case APPLIED -> applied(record, ack, result, now);
            case NO_OP -> new AckResult.Acked(
                    caseId, record.ackChoice(), orElse(record.ackChannel(), ack.channel()), orElse(record.ackedAt(), now), true);
            case REJECTED, DEFERRED -> rejected(record, result);
        };
    }

    private AckResult applied(CaseRecord record, CaseEvent.MerchantAck ack, TransitionResult result, Instant now) {
        AssuranceCase current = record.assuranceCase();
        UUID caseId = current.id();
        AckUpdate update = new AckUpdate(ack.choice(), ack.channel(), ack.actor(), ack.idempotencyKey(), now);
        cases.applyTransition(caseId, current.version(), result.nextState(), now, result.closeReason(), update);
        CaseContext context = new CaseContext(
                caseId,
                current.orderId(),
                current.orderVersion(),
                record.orderRef(),
                current.outletId(),
                result.nextState(),
                current.version() + 1,
                current.cancelledAt(),
                current.openedAt(),
                result.closeReason(),
                ack.choice(),
                ack.channel());
        effectApplier.apply(context, result.effects(), now);
        AfterCommit.run(() -> {
            meters.ack(ack.channel().name(), ack.choice().name());
            meters.caseClosed(CaseState.ACKNOWLEDGED.name());
            outboxWaker.getIfAvailable(() -> OutboxWaker.NOOP).wake();
        });
        return new AckResult.Acked(caseId, ack.choice(), ack.channel(), now, false);
    }

    private AckResult rejected(CaseRecord record, TransitionResult result) {
        String code = result.rejectionCode() == null ? "CASE_CLOSED" : result.rejectionCode();
        AssuranceCase current = record.assuranceCase();
        AfterCommit.run(() -> meters.ackRejected(code));
        return new AckResult.Rejected(
                code,
                current.id(),
                current.state(),
                current.closeReason(),
                record.ackChoice(),
                record.ackedAt());
    }

    private static Fact withVersionSeen(Fact fact, @Nullable Integer caseVersionSeen) {
        if (caseVersionSeen == null || !fact.kind().name().endsWith("_ACKNOWLEDGED")) {
            return fact;
        }
        Map<String, String> metadata = new HashMap<>(fact.metadata());
        metadata.put("caseVersionSeen", caseVersionSeen.toString());
        return new Fact(fact.kind(), metadata);
    }

    private static <T> T orElse(@Nullable T value, T fallback) {
        return value == null ? fallback : value;
    }
}
