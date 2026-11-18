package com.nocook.service.application;

import com.nocook.domain.effect.Fact;
import com.nocook.domain.effect.FactKind;
import com.nocook.domain.event.CaseEvent;
import com.nocook.domain.model.AckChannel;
import com.nocook.domain.model.AckChoice;
import com.nocook.service.application.AckService.AckResult;
import com.nocook.service.config.Iso;
import com.nocook.service.config.TraceIds;
import com.nocook.service.observability.NocookMeters;
import com.nocook.service.persistence.CallAttemptRepository;
import com.nocook.service.persistence.CallAttemptRepository.CallAttempt;
import com.nocook.service.persistence.CaseEventRepository;
import com.nocook.service.persistence.CaseRepository;
import com.nocook.service.persistence.CaseRepository.CaseRecord;
import com.nocook.service.persistence.OutboxRepository;
import com.nocook.service.persistence.WebhookEventRepository;
import com.nocook.service.telephony.TelephonyGateway;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Everything between the outbox PLACE_CALL intent and the provider: dialing (at most once), ending calls, and the
 * webhook status machine / DTMF path (ARCHITECTURE.md section 4).
 */
@Service
public class TelephonyService {

    public enum WebhookResult { OK, DUPLICATE, UNKNOWN_CALL }

    public record WebhookOutcome(WebhookResult result, @Nullable AckResult ack) {}

    private static final Logger log = LoggerFactory.getLogger(TelephonyService.class);

    private final CaseRepository cases;
    private final CaseEventRepository caseEvents;
    private final CallAttemptRepository callAttempts;
    private final WebhookEventRepository webhookEvents;
    private final OutboxRepository outbox;
    private final AckService ackService;
    private final TelephonyGateway gateway;
    private final NocookMeters meters;
    private final TraceIds traceIds;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public TelephonyService(
            CaseRepository cases,
            CaseEventRepository caseEvents,
            CallAttemptRepository callAttempts,
            WebhookEventRepository webhookEvents,
            OutboxRepository outbox,
            AckService ackService,
            TelephonyGateway gateway,
            NocookMeters meters,
            TraceIds traceIds,
            JsonMapper jsonMapper,
            Clock clock,
            TransactionTemplate transaction) {
        this.cases = cases;
        this.caseEvents = caseEvents;
        this.callAttempts = callAttempts;
        this.webhookEvents = webhookEvents;
        this.outbox = outbox;
        this.ackService = ackService;
        this.gateway = gateway;
        this.meters = meters;
        this.traceIds = traceIds;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.transaction = transaction;
    }

    // ---- outbox PLACE_CALL --------------------------------------------------------------------------------

    private enum DialDecision { DIAL, SKIP }

    /**
     * BACKEND.md section 3.3 PLACE_CALL branch. Case closed means CANCELLED. Otherwise RESERVED moves to DIALING in a
     * transaction (only one caller can win it), the gateway is called outside any transaction exactly once, and any
     * gateway failure leaves UNKNOWN: never retried, because UNKNOWN beats a duplicate call.
     */
    public void placeCall(UUID caseId, String outletId, String orderRef) {
        DialDecision decision = transaction.execute(status -> decideDial(caseId));
        if (decision != DialDecision.DIAL) {
            return;
        }
        String script = EffectApplier.CALL_SCRIPT_TEMPLATE.formatted(orderRef);
        TelephonyGateway.CallResult result;
        try {
            result = gateway.placeCall(new TelephonyGateway.CallRequest(caseId, outletId, orderRef, script));
        } catch (RuntimeException ex) {
            log.error("placeCall outcome unknown, not retrying caseId={} reason={}", caseId, ex.toString());
            transaction.executeWithoutResult(status -> recordUnknown(caseId, ex));
            return;
        }
        transaction.executeWithoutResult(status -> recordDialing(caseId, outletId, orderRef, script, result));
    }

    private DialDecision decideDial(UUID caseId) {
        Instant now = clock.instant();
        Optional<CaseRecord> record = cases.findById(caseId);
        boolean open = record.map(r -> r.assuranceCase().state().isOpen()).orElse(false);
        if (!open) {
            if (callAttempts.markCancelled(caseId, now)) {
                caseEvents.insertSystem(caseId, new Fact(FactKind.CALL_CANCELLED, Map.of("reason", "CASE_CLOSED")),
                        now, traceIds.current());
                meters.call("CANCELLED");
            }
            return DialDecision.SKIP;
        }
        return callAttempts.claimDialing(caseId, now) ? DialDecision.DIAL : DialDecision.SKIP;
    }

    private void recordUnknown(UUID caseId, RuntimeException cause) {
        Instant now = clock.instant();
        callAttempts.markUnknown(caseId, now);
        caseEvents.insertSystem(caseId, new Fact(FactKind.CALL_UNKNOWN, Map.of("reason", "GATEWAY_ERROR")),
                now, traceIds.current());
        meters.call("UNKNOWN");
    }

    private void recordDialing(
            UUID caseId, String outletId, String orderRef, String script, TelephonyGateway.CallResult result) {
        Instant now = clock.instant();
        callAttempts.setProviderCallId(caseId, result.providerCallId());
        caseEvents.insertSystem(caseId,
                new Fact(FactKind.CALL_DIALING, Map.of("providerCallId", result.providerCallId())), now, traceIds.current());
        meters.call("DIALING");
        // The INCOMING_CALL notification is a durable intent too, so an MQTT hiccup never loses it (and never re-dials).
        String actionKey = EffectApplier.incomingCallActionKey(caseId);
        ObjectNode call = jsonMapper.createObjectNode();
        call.put("messageId", actionKey);
        call.put("kind", "INCOMING_CALL");
        call.put("caseId", caseId.toString());
        call.put("providerCallId", result.providerCallId());
        call.put("orderRef", orderRef);
        call.put("outletId", outletId);
        call.put("script", script);
        outbox.insertIfAbsent(actionKey, OutboxRepository.TYPE_MQTT_CALL, caseId,
                jsonMapper.writeValueAsString(call), now, now);
    }

    // ---- EndCall ------------------------------------------------------------------------------------------

    /** Hang up at the provider and mark the attempt finished. Idempotent, best effort. */
    public void endCall(UUID caseId) {
        Optional<CallAttempt> attempt = callAttempts.findByCase(caseId);
        if (attempt.isEmpty() || attempt.get().providerCallId() == null) {
            return;
        }
        CallAttempt call = attempt.get();
        try {
            gateway.hangup(call.providerCallId());
        } catch (RuntimeException ex) {
            log.warn("hangup failed caseId={} providerCallId={} reason={}", caseId, call.providerCallId(), ex.toString());
        }
        transaction.executeWithoutResult(status -> {
            // Re-read under the row lock: a provider COMPLETED webhook may be racing with this hangup.
            Optional<CallAttempt> locked = callAttempts.findByProviderCallId(call.providerCallId(), true);
            if (locked.isEmpty() || locked.get().isTerminal()) {
                return;
            }
            Instant now = clock.instant();
            callAttempts.updateStatus(call.id(), "COMPLETED", null, now);
            caseEvents.insert(caseId,
                    new Fact(FactKind.CALL_COMPLETED,
                            Map.of("providerCallId", call.providerCallId(), "reason", "CASE_CLOSED")),
                    now, "IVR", null, null, traceIds.current());
        });
    }

    // ---- webhooks -----------------------------------------------------------------------------------------

    @Transactional
    public WebhookOutcome handleStatus(String providerCallId, String providerEventId, String status, @Nullable Instant at) {
        Instant now = clock.instant();
        Optional<CallAttempt> found = callAttempts.findByProviderCallId(providerCallId, true);
        if (found.isEmpty()) {
            return new WebhookOutcome(WebhookResult.UNKNOWN_CALL, null);
        }
        if (!webhookEvents.insertIfAbsent(providerEventId, providerCallId, "STATUS", now)) {
            return new WebhookOutcome(WebhookResult.DUPLICATE, null);
        }
        CallAttempt call = found.get();
        boolean regresses = call.isTerminal() || CallAttemptRepository.rank(status) <= CallAttemptRepository.rank(call.status());
        if (regresses) {
            return new WebhookOutcome(WebhookResult.OK, null);
        }
        boolean terminal = CallAttemptRepository.isTerminal(status);
        callAttempts.updateStatus(call.id(), status, "ANSWERED".equals(status) ? now : null, terminal ? now : null);
        caseEvents.insert(call.caseId(), new Fact(factKindFor(status),
                Map.of("providerCallId", providerCallId, "providerEventId", providerEventId)),
                now, "IVR", null, at, traceIds.current());
        meters.call(status);
        if ("ANSWERED".equals(status)) {
            hangUpIfCaseClosed(call, now);
        }
        return new WebhookOutcome(WebhookResult.OK, null);
    }

    private void hangUpIfCaseClosed(CallAttempt call, Instant now) {
        boolean open = cases.findById(call.caseId()).map(r -> r.assuranceCase().state().isOpen()).orElse(false);
        if (open) {
            return;
        }
        gateway.hangup(call.providerCallId());
        callAttempts.updateStatus(call.id(), "COMPLETED", null, now);
        caseEvents.insert(call.caseId(), new Fact(FactKind.CALL_CANCELLED,
                Map.of("providerCallId", call.providerCallId(), "reason", "CASE_CLOSED_ON_ANSWER")),
                now, "IVR", null, null, traceIds.current());
    }

    @Transactional
    public WebhookOutcome handleDtmf(String providerCallId, String providerEventId, String digits, @Nullable Instant at) {
        Instant now = clock.instant();
        Optional<CallAttempt> found = callAttempts.findByProviderCallId(providerCallId, true);
        if (found.isEmpty()) {
            return new WebhookOutcome(WebhookResult.UNKNOWN_CALL, null);
        }
        if (!webhookEvents.insertIfAbsent(providerEventId, providerCallId, "DTMF", now)) {
            return new WebhookOutcome(WebhookResult.DUPLICATE, null);
        }
        CallAttempt call = found.get();
        Optional<AckChoice> choice = AckChoice.fromDigit(digits);
        if (choice.isEmpty()) {
            caseEvents.insertRawKind(call.caseId(), "DTMF_IGNORED", now, "IVR", null,
                    Map.of("providerCallId", providerCallId, "digits", digits), traceIds.current());
            return new WebhookOutcome(WebhookResult.OK, null);
        }
        callAttempts.setOutcomeDigit(call.id(), digits.trim());
        CaseEvent.MerchantAck ack =
                new CaseEvent.MerchantAck(choice.get(), AckChannel.IVR, providerCallId, providerEventId);
        AckResult result = ackService.ack(call.caseId(), null, ack, null);
        return new WebhookOutcome(WebhookResult.OK, result);
    }

    private static FactKind factKindFor(String status) {
        return switch (status) {
            case "RINGING" -> FactKind.CALL_RINGING;
            case "ANSWERED" -> FactKind.CALL_ANSWERED;
            case "COMPLETED" -> FactKind.CALL_COMPLETED;
            case "NO_ANSWER" -> FactKind.CALL_NO_ANSWER;
            case "BUSY" -> FactKind.CALL_BUSY;
            case "FAILED" -> FactKind.CALL_FAILED;
            default -> FactKind.CALL_UNKNOWN;
        };
    }

    /** ISO helper for controllers. */
    public static String iso(Instant instant) {
        return Iso.format(instant);
    }
}
