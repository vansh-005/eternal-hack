package com.nocook.service.application;

import com.nocook.domain.effect.Effect;
import com.nocook.domain.event.CaseEvent;
import com.nocook.domain.model.AssuranceCase;
import com.nocook.domain.model.CaseState;
import com.nocook.domain.model.DeadlineKind;
import com.nocook.domain.model.Outcome;
import com.nocook.domain.model.Timings;
import com.nocook.domain.oms.OmsView;
import com.nocook.domain.policy.CasePolicy;
import com.nocook.domain.policy.TransitionResult;
import com.nocook.service.config.TraceIds;
import com.nocook.service.observability.NocookMeters;
import com.nocook.service.persistence.CaseEventRepository;
import com.nocook.service.persistence.CaseRepository;
import com.nocook.service.persistence.CaseRepository.CaseRecord;
import com.nocook.service.persistence.DeadlineRepository;
import com.nocook.service.persistence.DeadlineRepository.ClaimedDeadline;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One claimed deadline, one transaction (BACKEND.md section 3.2): lock the case, run {@link CasePolicy#transition},
 * persist, apply effects, settle the deadline row. The OMS read happened before, outside the transaction.
 */
@Service
public class EscalationService {

    /** previousState / nextState are null when the case row no longer exists. */
    public record EscalationResult(
            Outcome outcome, DeadlineKind kind, @Nullable CaseState previousState, @Nullable CaseState nextState) {}

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    private final CaseRepository cases;
    private final CaseEventRepository caseEvents;
    private final DeadlineRepository deadlines;
    private final EffectApplier effectApplier;
    private final ObjectProvider<OutboxWaker> outboxWaker;
    private final NocookMeters meters;
    private final TraceIds traceIds;
    private final Clock clock;
    private final Timings timings;

    public EscalationService(
            CaseRepository cases,
            CaseEventRepository caseEvents,
            DeadlineRepository deadlines,
            EffectApplier effectApplier,
            ObjectProvider<OutboxWaker> outboxWaker,
            NocookMeters meters,
            TraceIds traceIds,
            Clock clock,
            Timings timings) {
        this.cases = cases;
        this.caseEvents = caseEvents;
        this.deadlines = deadlines;
        this.effectApplier = effectApplier;
        this.outboxWaker = outboxWaker;
        this.meters = meters;
        this.traceIds = traceIds;
        this.clock = clock;
        this.timings = timings;
    }

    @Transactional
    public EscalationResult applyDeadline(ClaimedDeadline claimed, OmsView omsView) {
        Instant now = clock.instant();
        Optional<CaseRecord> locked = cases.lockById(claimed.caseId());
        if (locked.isEmpty()) {
            deadlines.markDone(claimed.id(), now);
            log.warn("deadline for missing case caseId={} kind={}", claimed.caseId(), claimed.kind());
            return new EscalationResult(Outcome.NO_OP, claimed.kind(), null, null);
        }
        CaseRecord record = locked.get();
        AssuranceCase current = record.assuranceCase();
        CaseEvent.DeadlineDue event = new CaseEvent.DeadlineDue(claimed.kind(), claimed.dueAt());
        TransitionResult result = CasePolicy.transition(current, event, omsView, now, timings);
        String traceId = traceIds.current();
        caseEvents.insertAllSystem(current.id(), result.facts(), now, traceId);

        if (result.outcome() == Outcome.DEFERRED) {
            // Effect RescheduleDeadline puts this very deadline back to PENDING.
            effectApplier.apply(contextAfter(record, result, current.version()), result.effects(), now);
        } else {
            // Settle the claimed row first so CancelPendingDeadlines (which covers PENDING and LEASED) skips it.
            deadlines.markDone(claimed.id(), now);
            if (result.outcome() == Outcome.APPLIED) {
                persistApplied(record, claimed, result, now);
            }
        }
        scheduleAfterCommit(claimed, current.state(), result);
        return new EscalationResult(result.outcome(), claimed.kind(), current.state(), result.nextState());
    }

    private void persistApplied(CaseRecord record, ClaimedDeadline claimed, TransitionResult result, Instant now) {
        AssuranceCase current = record.assuranceCase();
        Instant closedAt = result.closeReason() == null ? null : now;
        cases.applyTransition(
                current.id(), current.version(), result.nextState(), closedAt, result.closeReason(), null);
        List<Effect> effects = new ArrayList<>(result.effects());
        if (current.state() == CaseState.ESCALATED_IVR
                && !result.nextState().isOpen()
                && effects.stream().noneMatch(effect -> effect instanceof Effect.EndCall)) {
            // DECISIONS gap: D4-D6 emit no EndCall; a call that is still active must be ended when the case closes.
            effects.add(new Effect.EndCall());
        }
        if (result.nextState() == CaseState.ESCALATED_IVR) {
            // D9: the service retires a still-pending L1 deadline (L1 was deferred past the IVR time).
            deadlines.cancelPendingOfKind(current.id(), DeadlineKind.L1);
        }
        effectApplier.apply(contextAfter(record, result, current.version() + 1), effects, now);
    }

    private static CaseContext contextAfter(CaseRecord record, TransitionResult result, int versionAfter) {
        AssuranceCase current = record.assuranceCase();
        return new CaseContext(
                current.id(),
                current.orderId(),
                current.orderVersion(),
                record.orderRef(),
                current.outletId(),
                result.nextState(),
                versionAfter,
                current.cancelledAt(),
                current.openedAt(),
                result.closeReason(),
                record.ackChoice(),
                record.ackChannel());
    }

    private void scheduleAfterCommit(ClaimedDeadline claimed, CaseState previous, TransitionResult result) {
        String kind = claimed.kind().name();
        AfterCommit.run(() -> {
            meters.escalation(kind, outcomeLabel(result, previous));
            if (result.outcome() == Outcome.APPLIED) {
                meters.deadlineLateness(kind, Duration.between(claimed.dueAt(), clock.instant()));
                if (!result.nextState().isOpen()) {
                    meters.caseClosed(result.nextState().name());
                }
            }
            outboxWaker.getIfAvailable(() -> OutboxWaker.NOOP).wake();
        });
    }

    /** nocook.escalations outcome: fired | blocked | deferred | noop. */
    static String outcomeLabel(TransitionResult result, CaseState previous) {
        return switch (result.outcome()) {
            case DEFERRED -> "deferred";
            case NO_OP, REJECTED -> "noop";
            case APPLIED -> result.nextState() == CaseState.SUPERSEDED ? "blocked" : "fired";
        };
    }
}
