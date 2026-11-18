package com.nocook.service.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes durable intents. Claim/lease/mark-sent SQL is added by B-03 (dispatcher). */
@Repository
public class OutboxRepository {

    public static final String TYPE_MQTT_CARD = "MQTT_CARD";
    public static final String TYPE_MQTT_CALL = "MQTT_CALL";
    public static final String TYPE_PLACE_CALL = "PLACE_CALL";

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert an intent; UNIQUE(action_key) makes repeats no-ops. Returns false when the key already existed. */
    public boolean insertIfAbsent(
            String actionKey, String type, UUID caseId, String payloadJson, Instant availableAt, Instant createdAt) {
        int inserted = jdbc.sql("""
                        insert into nocook.outbox (action_key, type, case_id, payload, available_at, created_at)
                        values (:actionKey, :type, :caseId, cast(:payload as jsonb), :availableAt, :createdAt)
                        on conflict (action_key) do nothing""")
                .param("actionKey", actionKey)
                .param("type", type)
                .param("caseId", caseId)
                .param("payload", payloadJson)
                .param("availableAt", CaseRepository.utc(availableAt))
                .param("createdAt", CaseRepository.utc(createdAt))
                .update();
        return inserted == 1;
    }
}
