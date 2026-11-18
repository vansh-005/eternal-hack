package com.nocook.oms.outbox;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    /** One claimed outbox row. payloadJson still contains the "traceparent" key (see OutboxRelay). */
    public record OutboxRow(UUID eventId, String routingKey, String payloadJson, Instant createdAt) {}

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID eventId, String routingKey, String payloadJson) {
        jdbc.sql("""
                        insert into oms.outbox (event_id, routing_key, payload)
                        values (:eventId, :routingKey, cast(:payload as jsonb))""")
                .param("eventId", eventId)
                .param("routingKey", routingKey)
                .param("payload", payloadJson)
                .update();
    }

    public List<OutboxRow> claimPending(int batchSize) {
        return jdbc.sql("""
                        select event_id, routing_key, payload::text as payload, created_at
                        from oms.outbox
                        where status = 'PENDING'
                        order by created_at, id
                        limit :batchSize
                        for update skip locked""")
                .param("batchSize", batchSize)
                .query((rs, rowNumber) -> new OutboxRow(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("routing_key"),
                        rs.getString("payload"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public void markSent(Collection<UUID> eventIds, Instant sentAt) {
        jdbc.sql("update oms.outbox set status = 'SENT', sent_at = :sentAt where event_id = any(:ids)")
                .param("sentAt", OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC))
                .param("ids", eventIds.toArray(new UUID[0]))
                .update();
    }

    public long countPending() {
        return jdbc.sql("select count(*) from oms.outbox where status = 'PENDING'")
                .query(Long.class)
                .single();
    }
}
