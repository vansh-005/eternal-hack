package com.nocook.service.application;

import com.nocook.domain.effect.Fact;
import com.nocook.domain.effect.FactKind;
import com.nocook.service.config.TraceIds;
import com.nocook.service.observability.NocookMeters;
import com.nocook.service.persistence.CaseEventRepository;
import com.nocook.service.persistence.CaseRepository;
import com.nocook.service.persistence.CaseRepository.CaseRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tablet receipts (API.md 1.2). Receipts are facts and timers only: they never change case state (CLAUDE.md rule 5).
 * The first receipt per (case, device, kind, messageId) is recorded; repeats are ignored.
 */
@Service
public class ReceiptService {

    public enum ReceiptKind { DELIVERED, RENDERED, DISMISSED }

    public enum ReceiptResult { RECORDED, DUPLICATE, CASE_NOT_FOUND }

    private final CaseRepository cases;
    private final CaseEventRepository caseEvents;
    private final NocookMeters meters;
    private final TraceIds traceIds;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ReceiptService(
            CaseRepository cases,
            CaseEventRepository caseEvents,
            NocookMeters meters,
            TraceIds traceIds,
            Clock clock,
            TransactionTemplate transaction) {
        this.cases = cases;
        this.caseEvents = caseEvents;
        this.meters = meters;
        this.traceIds = traceIds;
        this.clock = clock;
        this.transaction = transaction;
    }

    /** Swarm devices are named {@code swarm-*}; everything else is a human tablet. */
    public static String clientOf(String deviceId) {
        return deviceId.startsWith("swarm-") ? "swarm" : "tablet";
    }

    public ReceiptResult record(
            UUID caseId, ReceiptKind kind, String deviceId, String messageId, @Nullable Instant deviceAt) {
        Optional<CaseRecord> found = cases.findById(caseId);
        if (found.isEmpty()) {
            return ReceiptResult.CASE_NOT_FOUND;
        }
        CaseRecord record = found.get();
        String key = caseId + "|" + deviceId + "|" + kind + "|" + messageId;
        Boolean recorded = transaction.execute(status -> {
            caseEvents.lockReceiptKey(key);
            boolean firstOfKindForDevice = caseEvents.countForDevice(caseId, factKind(kind).name(), deviceId) == 0;
            Instant now = clock.instant();
            Fact fact = new Fact(factKind(kind), Map.of("deviceId", deviceId, "messageId", messageId));
            boolean inserted = caseEvents.insertReceiptIfAbsent(
                    caseId, fact, deviceId, messageId, now, deviceAt, traceIds.current());
            if (inserted) {
                observe(kind, deviceId, record, firstOfKindForDevice, now);
            }
            return inserted;
        });
        return Boolean.TRUE.equals(recorded) ? ReceiptResult.RECORDED : ReceiptResult.DUPLICATE;
    }

    private void observe(ReceiptKind kind, String deviceId, CaseRecord record, boolean firstForDevice, Instant now) {
        String client = clientOf(deviceId);
        meters.receipt(kind.name(), client);
        if (!firstForDevice) {
            return;
        }
        Instant openedAt = record.assuranceCase().openedAt();
        switch (kind) {
            case DELIVERED -> meters.cardDelivery(client, Duration.between(openedAt, now));
            case RENDERED -> meters.cardRender(client, Duration.between(openedAt, now));
            case DISMISSED -> {
                if (record.ackedAt() != null) {
                    meters.ackFanout(client, Duration.between(record.ackedAt(), now));
                }
            }
        }
    }

    private static FactKind factKind(ReceiptKind kind) {
        return switch (kind) {
            case DELIVERED -> FactKind.CARD_DELIVERED;
            case RENDERED -> FactKind.CARD_RENDERED;
            case DISMISSED -> FactKind.CARD_DISMISSED;
        };
    }
}
