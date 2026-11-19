package com.nocook.service.persistence;

import com.nocook.service.observability.Spans;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Durable intents: insert, claim with SKIP LOCKED, mark sent/failed, reap expired leases (DATA_MODEL.md section 3). */
@Repository
public class OutboxRepository {

    public static final String TYPE_MQTT_CARD = "MQTT_CARD";
    public static final String TYPE_MQTT_CALL = "MQTT_CALL";
    public static final String TYPE_PLACE_CALL = "PLACE_CALL";

    public record ClaimedOutbox(
            UUID id, String actionKey, String type, UUID caseId, String payload, int attempts, Instant createdAt) {}

    public record ReapedOutbox(UUID caseId, String type, String previousOwner) {}

    public static final String TRACEPARENT_KEY = "traceparent";

    private final JdbcClient jdbc;
    private final Spans spans;

    public OutboxRepository(JdbcClient jdbc, Spans spans) {
        this.jdbc = jdbc;
        this.spans = spans;
    }

    /** Insert an intent; UNIQUE(action_key) makes repeats no-ops. Returns false when the key already existed. */
    public boolean insertIfAbsent(
            String actionKey, String type, UUID caseId, String payloadJson, Instant availableAt, Instant createdAt) {
        int inserted = jdbc.sql("""
                        insert into nocook.outbox (action_key, type, case_id, payload, available_at, created_at)
                        values (:actionKey, :type, :caseId,
                                cast(:payload as jsonb) || jsonb_build_object('traceparent', cast(:traceparent as text)),
                                :availableAt, :createdAt)
                        on conflict (action_key) do nothing""")
                .param("actionKey", actionKey)
                .param("type", type)
                .param("caseId", caseId)
                .param("payload", payloadJson)
                .param("traceparent", spans.currentTraceparent())
                .param("availableAt", CaseRepository.utc(availableAt))
                .param("createdAt", CaseRepository.utc(createdAt))
                .update();
        return inserted == 1;
    }

    /** Claim ready rows (same shape as the deadline claim). */
    public List<ClaimedOutbox> claimReady(Instant now, int batchSize, String workerId, Instant leaseUntil) {
        return jdbc.sql("""
                        with ready as (
                            select id
                            from nocook.outbox
                            where status = 'PENDING' and available_at <= :now
                            order by available_at
                            limit :batchSize
                            for update skip locked
                        )
                        update nocook.outbox o
                        set status = 'LEASED', lease_owner = :workerId, lease_until = :leaseUntil, attempts = o.attempts + 1
                        from ready
                        where o.id = ready.id
                        returning o.id, o.action_key, o.type, o.case_id, o.payload::text as payload, o.attempts, o.created_at""")
                .param("now", CaseRepository.utc(now))
                .param("batchSize", batchSize)
                .param("workerId", workerId)
                .param("leaseUntil", CaseRepository.utc(leaseUntil))
                .query((rs, rowNum) -> new ClaimedOutbox(
                        rs.getObject("id", UUID.class),
                        rs.getString("action_key"),
                        rs.getString("type"),
                        rs.getObject("case_id", UUID.class),
                        rs.getString("payload"),
                        rs.getInt("attempts"),
                        CaseRepository.instant(rs, "created_at")))
                .list();
    }

    /** Idempotent: a row already SENT stays SENT. Returns true when this call moved it to SENT. */
    public boolean markSent(UUID id, Instant now) {
        return jdbc.sql("""
                        update nocook.outbox
                        set status = 'SENT', sent_at = :now, lease_owner = null, lease_until = null, last_error = null
                        where id = :id and status in ('LEASED','PENDING')""")
                        .param("now", CaseRepository.utc(now))
                        .param("id", id)
                        .update()
                == 1;
    }

    /** Publish failed: back to PENDING at {@code retryAt}, or FAILED when {@code terminal}. */
    public void markFailedAttempt(UUID id, Instant retryAt, boolean terminal, String error) {
        String trimmed = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        jdbc.sql("""
                        update nocook.outbox
                        set status = :status, available_at = :retryAt, lease_owner = null, lease_until = null,
                            last_error = :error
                        where id = :id and status = 'LEASED'""")
                .param("status", terminal ? "FAILED" : "PENDING")
                .param("retryAt", CaseRepository.utc(retryAt))
                .param("error", trimmed)
                .param("id", id)
                .update();
    }

    /** Expired leases back to PENDING, reporting the previous owner (DATA_MODEL.md section 3 reaper shape). */
    public List<ReapedOutbox> reapExpiredLeases(Instant now) {
        return jdbc.sql("""
                        with expired as (
                            select id, case_id, type, lease_owner as previous_owner
                            from nocook.outbox
                            where status = 'LEASED' and lease_until < :now
                            for update skip locked
                        )
                        update nocook.outbox o
                        set status = 'PENDING', lease_owner = null, lease_until = null
                        from expired
                        where o.id = expired.id
                        returning expired.case_id, expired.type, expired.previous_owner""")
                .param("now", CaseRepository.utc(now))
                .query((rs, rowNum) -> new ReapedOutbox(
                        rs.getObject("case_id", UUID.class), rs.getString("type"), rs.getString("previous_owner")))
                .list();
    }
}
