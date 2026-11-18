package com.nocook.service.ingestion;

import com.nocook.domain.model.CancelInitiator;
import com.nocook.domain.model.CancellationEvent;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** API.md section 3 payload check (schemaVersion 1, required fields). Invalid input throws {@link InvalidEventException}. */
@Component
public class EventValidator {

    public static class InvalidEventException extends RuntimeException {
        public InvalidEventException(String message) {
            super(message);
        }
    }

    private final JsonMapper jsonMapper;

    public EventValidator(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public CancellationEvent validate(byte[] body) {
        JsonNode root = parse(body);
        if (!root.path("schemaVersion").isInt() || root.get("schemaVersion").asInt() != 1) {
            throw new InvalidEventException("unsupported schemaVersion");
        }
        String type = text(root, "type", false);
        if (type != null && !"order.cancelled".equals(type)) {
            throw new InvalidEventException("unexpected type " + type);
        }
        String eventId = text(root, "eventId", true);
        try {
            UUID.fromString(eventId);
        } catch (IllegalArgumentException ex) {
            throw new InvalidEventException("eventId is not a UUID");
        }
        String orderId = text(root, "orderId", true);
        String outletId = text(root, "outletId", true);
        JsonNode version = root.path("orderVersion");
        if (!version.isInt() || version.asInt() < 1) {
            throw new InvalidEventException("orderVersion must be a positive int");
        }
        Instant occurredAt = instant(text(root, "occurredAt", true));
        CancelInitiator initiator = initiator(text(root, "initiatedBy", true));
        String runId = text(root, "runId", false);
        return new CancellationEvent(eventId, orderId, outletId, version.asInt(), occurredAt, initiator, runId);
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode root = jsonMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new InvalidEventException("payload is not a JSON object");
            }
            return root;
        } catch (InvalidEventException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new InvalidEventException("payload is not valid JSON");
        }
    }

    private static @Nullable String text(JsonNode root, String field, boolean required) {
        JsonNode value = root.get(field);
        boolean missing = value == null || value.isNull();
        if (missing) {
            if (required) {
                throw new InvalidEventException("missing " + field);
            }
            return null;
        }
        if (!value.isString() || (required && value.asString().isBlank())) {
            throw new InvalidEventException("bad " + field);
        }
        return value.asString();
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            throw new InvalidEventException("occurredAt is not ISO-8601");
        }
    }

    private static CancelInitiator initiator(String value) {
        try {
            return CancelInitiator.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new InvalidEventException("unknown initiatedBy " + value);
        }
    }
}
