package com.nocook.service.application;

import com.nocook.domain.effect.Fact;
import com.nocook.domain.effect.FactKind;
import com.nocook.domain.model.CancellationEvent;
import com.nocook.domain.model.CaseState;
import com.nocook.domain.model.OrderSnapshot;
import com.nocook.domain.model.Timings;
import com.nocook.domain.policy.CasePolicy;
import com.nocook.domain.policy.OpenDecision;
import com.nocook.service.config.TraceIds;
import com.nocook.service.persistence.CaseEventRepository;
import com.nocook.service.persistence.CaseRepository;
import com.nocook.service.persistence.CaseRepository.NewCase;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** DOMAIN.md section 2 in one transaction: decide, insert the case, its facts, and apply the effects. */
@Service
public class CaseOpeningService {

    public enum OpenOutcome { OPENED, NOT_ACTIONABLE, DUPLICATE }

    public record OpenResult(OpenOutcome outcome, UUID caseId, Instant openedAt) {}

    private static final Logger log = LoggerFactory.getLogger(CaseOpeningService.class);

    private final CaseRepository cases;
    private final CaseEventRepository caseEvents;
    private final EffectApplier effectApplier;
    private final ObjectProvider<OutboxWaker> outboxWaker;
    private final TraceIds traceIds;
    private final Clock clock;
    private final Timings timings;

    public CaseOpeningService(
            CaseRepository cases,
            CaseEventRepository caseEvents,
            EffectApplier effectApplier,
            ObjectProvider<OutboxWaker> outboxWaker,
            TraceIds traceIds,
            Clock clock,
            Timings timings) {
        this.cases = cases;
        this.caseEvents = caseEvents;
        this.effectApplier = effectApplier;
        this.outboxWaker = outboxWaker;
        this.traceIds = traceIds;
        this.clock = clock;
        this.timings = timings;
    }

    @Transactional
    public OpenResult open(CancellationEvent event, OrderSnapshot freshSnapshot) {
        Instant now = clock.instant();
        String traceId = traceIds.current();
        OpenDecision decision = CasePolicy.decideOpen(event, freshSnapshot, now, timings);
        boolean eligible = decision.initialState() == CaseState.AWAITING_ACK;
        Instant closedAt = eligible ? null : now;

        NewCase newCase = new NewCase(
                event.orderId(),
                event.orderVersion(),
                event.outletId(),
                decision.initialState(),
                decision.reason() == null ? null : decision.reason().name(),
                event.cancelledAt(),
                now,
                closedAt,
                freshSnapshot.orderRef(),
                event.eventId(),
                event.runId(),
                traceId);
        Optional<UUID> insertedId = cases.insertIfAbsent(newCase);
        if (insertedId.isEmpty()) {
            return recordDuplicate(event, now, traceId);
        }

        UUID caseId = insertedId.get();
        caseEvents.insertAllSystem(caseId, decision.facts(), now, traceId);
        CaseContext context = new CaseContext(
                caseId,
                event.orderId(),
                event.orderVersion(),
                freshSnapshot.orderRef(),
                event.outletId(),
                decision.initialState(),
                CaseRepository.INITIAL_VERSION,
                event.cancelledAt(),
                now,
                null,
                null,
                null);
        effectApplier.apply(context, decision.effects(), now);
        if (eligible) {
            runAfterCommit(() -> outboxWaker.getIfAvailable(() -> OutboxWaker.NOOP).wake());
        }
        OpenOutcome outcome = eligible ? OpenOutcome.OPENED : OpenOutcome.NOT_ACTIONABLE;
        return new OpenResult(outcome, caseId, now);
    }

    private OpenResult recordDuplicate(CancellationEvent event, Instant now, String traceId) {
        Optional<UUID> existing = cases.findIdByOrderVersion(event.orderId(), event.orderVersion());
        if (existing.isEmpty()) {
            // Lost to cases_one_open_per_order: a different version of the order already has an open case.
            existing = cases.findOpenIdByOrder(event.orderId());
            log.warn("open conflict on open-case index orderId={} orderVersion={}", event.orderId(), event.orderVersion());
        }
        UUID existingId = existing.orElseThrow(() -> new IllegalStateException(
                "conflict without an existing case orderId=" + event.orderId()));
        Fact duplicate = new Fact(FactKind.DUPLICATE_EVENT, Map.of("eventId", event.eventId()));
        caseEvents.insertSystem(existingId, duplicate, now, traceId);
        return new OpenResult(OpenOutcome.DUPLICATE, existingId, now);
    }

    private static void runAfterCommit(Runnable hook) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            hook.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                hook.run();
            }
        });
    }
}
