package com.nocook.service.api;

import com.nocook.service.application.TelephonyService;
import com.nocook.service.application.TelephonyService.WebhookOutcome;
import com.nocook.service.application.TelephonyService.WebhookResult;
import com.nocook.service.observability.NocookMeters;
import com.nocook.service.telephony.WebhookSigner;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a real provider would call (API.md 1.8). Signature is verified over the RAW body bytes before anything is
 * parsed; replays (same providerEventId) have no effect.
 */
@RestController
@RequestMapping("/api/webhooks/telephony")
public class TelephonyWebhookController {

    private static final Set<String> STATUSES =
            Set.of("RINGING", "ANSWERED", "COMPLETED", "NO_ANSWER", "BUSY", "FAILED", "UNKNOWN");

    private final WebhookSigner signer;
    private final TelephonyService telephony;
    private final NocookMeters meters;
    private final JsonMapper jsonMapper;

    public TelephonyWebhookController(
            WebhookSigner signer, TelephonyService telephony, NocookMeters meters, JsonMapper jsonMapper) {
        this.signer = signer;
        this.telephony = telephony;
        this.meters = meters;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestBody byte[] raw,
            @RequestHeader(value = WebhookSigner.SIGNATURE_HEADER, required = false) @Nullable String signature) {
        verify("status", raw, signature);
        JsonNode body = parse(raw);
        String callId = required(body, "providerCallId");
        String eventId = required(body, "providerEventId");
        String status = required(body, "status");
        if (!STATUSES.contains(status)) {
            throw validation("status");
        }
        WebhookOutcome outcome = telephony.handleStatus(callId, eventId, status, instant(body));
        return respond("status", outcome);
    }

    @PostMapping("/dtmf")
    public ResponseEntity<Map<String, Object>> dtmf(
            @RequestBody byte[] raw,
            @RequestHeader(value = WebhookSigner.SIGNATURE_HEADER, required = false) @Nullable String signature) {
        verify("dtmf", raw, signature);
        JsonNode body = parse(raw);
        String callId = required(body, "providerCallId");
        String eventId = required(body, "providerEventId");
        String digits = required(body, "digits");
        WebhookOutcome outcome = telephony.handleDtmf(callId, eventId, digits, instant(body));
        return respond("dtmf", outcome);
    }

    private void verify(String kind, byte[] raw, @Nullable String signature) {
        if (!signer.verify(raw, signature)) {
            meters.webhook(kind, "bad_signature");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_SIGNATURE", "Webhook signature is missing or invalid");
        }
    }

    private ResponseEntity<Map<String, Object>> respond(String kind, WebhookOutcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (outcome.result() == WebhookResult.DUPLICATE) {
            meters.webhook(kind, "duplicate");
            body.put("duplicate", true);
        } else if (outcome.result() == WebhookResult.UNKNOWN_CALL) {
            body.put("ignored", true);
            body.put("reason", "UNKNOWN_CALL");
        } else {
            meters.webhook(kind, "ok");
            body.put("duplicate", false);
            if (outcome.ack() != null) {
                body.put("ack", outcome.ack().getClass().getSimpleName());
            }
        }
        return ResponseEntity.ok(body);
    }

    private JsonNode parse(byte[] raw) {
        try {
            JsonNode node = jsonMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                throw validation("body");
            }
            return node;
        } catch (RuntimeException ex) {
            if (ex instanceof ApiException apiException) {
                throw apiException;
            }
            throw validation("body");
        }
    }

    private static String required(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw validation(field);
        }
        return value.asString();
    }

    private static @Nullable Instant instant(JsonNode body) {
        JsonNode value = body.get("at");
        if (value == null || !value.isString()) {
            return null;
        }
        try {
            return Instant.parse(value.asString());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static ApiException validation(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Missing or invalid field: " + field);
    }
}
