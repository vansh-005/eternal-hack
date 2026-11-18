package com.nocook.service.persistence;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Dedupe of provider callbacks by providerEventId (primary key). */
@Repository
public class WebhookEventRepository {

    private final JdbcClient jdbc;

    public WebhookEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns false when the event id was already recorded (a replay). */
    public boolean insertIfAbsent(String providerEventId, String providerCallId, String kind, Instant receivedAt) {
        return jdbc.sql("""
                        insert into nocook.webhook_events (provider_event_id, provider_call_id, kind, received_at)
                        values (:eventId, :callId, :kind, :at)
                        on conflict (provider_event_id) do nothing""")
                        .param("eventId", providerEventId)
                        .param("callId", providerCallId)
                        .param("kind", kind)
                        .param("at", CaseRepository.utc(receivedAt))
                        .update()
                == 1;
    }
}
