package com.nocook.service.scheduling;

import com.nocook.domain.effect.Fact;
import com.nocook.domain.effect.FactKind;
import com.nocook.service.application.OutboxWaker;
import com.nocook.service.application.TelephonyService;
import com.nocook.service.config.Iso;
import com.nocook.service.config.TraceIds;
import com.nocook.service.config.WorkerProperties;
import com.nocook.service.notifications.CardPublisher;
import com.nocook.service.observability.NocookMeters;
import com.nocook.service.persistence.CaseEventRepository;
import com.nocook.service.persistence.OutboxRepository;
import com.nocook.service.persistence.OutboxRepository.ClaimedOutbox;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * BACKEND.md section 3.3. Claims PENDING outbox rows (SKIP LOCKED, 10 s lease), dispatches them after commit and marks
 * them SENT. Rows of one case are dispatched in order; different cases in parallel on virtual threads.
 *
 * <ul>
 *   <li>MQTT_CARD / MQTT_CALL: publish QoS 1, await PUBACK; fact CARD_DISPATCHED for cards. Failure: back to PENDING at
 *       now + 500 ms x attempts, FAILED after 10 attempts.
 *   <li>PLACE_CALL: {@link TelephonyService#placeCall} (re-checks the case, never retries a dial).
 * </ul>
 *
 * Also the {@link OutboxWaker}: {@link #wake()} drains immediately instead of waiting for the next tick.
 */
@Component
public class OutboxDispatcher implements OutboxWaker, DisposableBean {

    public static final int MAX_ATTEMPTS = 10;
    public static final Duration BACKOFF_STEP = Duration.ofMillis(500);

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outbox;
    private final CaseEventRepository caseEvents;
    private final CardPublisher publisher;
    private final TelephonyService telephony;
    private final NocookMeters meters;
    private final TraceIds traceIds;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final WorkerProperties.Outbox properties;
    private final String workerId;
    private final boolean schedulingEnabled;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean ticking = new AtomicBoolean();

    public OutboxDispatcher(
            OutboxRepository outbox,
            CaseEventRepository caseEvents,
            CardPublisher publisher,
            TelephonyService telephony,
            NocookMeters meters,
            TraceIds traceIds,
            JsonMapper jsonMapper,
            Clock clock,
            TransactionTemplate transaction,
            WorkerProperties.Outbox properties,
            WorkerProperties.Replica replica,
            @org.springframework.beans.factory.annotation.Value("${nocook.scheduling.enabled:true}") boolean schedulingEnabled) {
        this.schedulingEnabled = schedulingEnabled;
        this.outbox = outbox;
        this.caseEvents = caseEvents;
        this.publisher = publisher;
        this.telephony = telephony;
        this.meters = meters;
        this.traceIds = traceIds;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.transaction = transaction;
        this.properties = properties;
        this.workerId = replica.replicaId() + "-ob-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${nocook.outbox.tick:50ms}")
    public void scheduledTick() {
        try {
            tick();
        } catch (RuntimeException ex) {
            log.error("outbox tick failed reason={}", ex.toString(), ex);
        }
    }

    /** Drain on a virtual thread so the committing request thread never waits for MQTT. */
    @Override
    public void wake() {
        if (!schedulingEnabled) {
            return;
        }
        executor.submit(() -> {
            try {
                tick();
            } catch (RuntimeException ex) {
                log.error("outbox wake failed reason={}", ex.toString(), ex);
            }
        });
    }

    /** Claim and dispatch until nothing is ready. Public for tests. Returns the number of rows dispatched. */
    public int tick() {
        if (!ticking.compareAndSet(false, true)) {
            return 0;
        }
        int dispatched = 0;
        try {
            while (true) {
                Instant now = clock.instant();
                List<ClaimedOutbox> claimed =
                        outbox.claimReady(now, properties.batch(), workerId, now.plus(properties.lease()));
                if (claimed.isEmpty()) {
                    break;
                }
                dispatchBatch(claimed);
                dispatched += claimed.size();
            }
        } finally {
            ticking.set(false);
        }
        return dispatched;
    }

    private void dispatchBatch(List<ClaimedOutbox> claimed) {
        Map<UUID, List<ClaimedOutbox>> byCase = new LinkedHashMap<>();
        for (ClaimedOutbox row : claimed) {
            byCase.computeIfAbsent(row.caseId(), key -> new ArrayList<>()).add(row);
        }
        List<Future<?>> futures = new ArrayList<>();
        for (List<ClaimedOutbox> caseRows : byCase.values()) {
            caseRows.sort(Comparator.comparing(ClaimedOutbox::createdAt));
            futures.add(executor.submit(() -> caseRows.forEach(this::dispatchSafely)));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException ex) {
                log.error("outbox dispatch task failed reason={}", ex.getCause().toString());
            }
        }
    }

    private void dispatchSafely(ClaimedOutbox row) {
        try {
            dispatch(row);
        } catch (RuntimeException ex) {
            recordFailure(row, ex);
        }
    }

    private void dispatch(ClaimedOutbox row) {
        JsonNode payload = jsonMapper.readTree(row.payload());
        String outletId = payload.path("outletId").asString();
        switch (row.type()) {
            case OutboxRepository.TYPE_MQTT_CARD -> {
                publisher.publishCard(outletId, stamped(payload));
                markSent(row, new Fact(FactKind.CARD_DISPATCHED,
                        Map.of("cardKind", payload.path("kind").asString(), "messageId", row.actionKey())));
            }
            case OutboxRepository.TYPE_MQTT_CALL -> {
                if ("CALL_ENDED".equals(payload.path("kind").asString())) {
                    telephony.endCall(row.caseId());
                }
                publisher.publishCall(outletId, stamped(payload));
                markSent(row, null);
            }
            case OutboxRepository.TYPE_PLACE_CALL -> {
                telephony.placeCall(row.caseId(), outletId, payload.path("orderRef").asString());
                markSent(row, null);
            }
            default -> throw new IllegalStateException("unknown outbox type " + row.type());
        }
    }

    /** The dispatcher stamps sentAt (API.md section 4) at publish time. */
    private String stamped(JsonNode payload) {
        ObjectNode copy = ((ObjectNode) payload).deepCopy();
        copy.put("sentAt", Iso.format(clock.instant()));
        return jsonMapper.writeValueAsString(copy);
    }

    private void markSent(ClaimedOutbox row, Fact fact) {
        Instant now = clock.instant();
        transaction.executeWithoutResult(status -> {
            boolean moved = outbox.markSent(row.id(), now);
            if (fact != null && moved) {
                caseEvents.insertSystem(row.caseId(), fact, now, traceIds.current());
            }
        });
        meters.outboxDispatch(row.type(), Duration.between(row.createdAt(), now));
    }

    private void recordFailure(ClaimedOutbox row, RuntimeException ex) {
        Instant now = clock.instant();
        boolean terminal = row.attempts() >= MAX_ATTEMPTS;
        Instant retryAt = now.plus(BACKOFF_STEP.multipliedBy(row.attempts()));
        log.warn("outbox dispatch failed type={} caseId={} attempt={} terminal={} reason={}",
                row.type(), row.caseId(), row.attempts(), terminal, ex.toString());
        outbox.markFailedAttempt(row.id(), retryAt, terminal, ex.toString());
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
