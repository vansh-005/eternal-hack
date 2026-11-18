package com.nocook.service.persistence;

import com.nocook.domain.effect.Fact;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Appends facts to {@code nocook.case_events}. Metadata maps are stored as jsonb. */
@Repository
public class CaseEventRepository {

    public static final String CHANNEL_SYSTEM = "SYSTEM";

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public CaseEventRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public void insert(
            UUID caseId,
            Fact fact,
            Instant serverAt,
            String channel,
            @Nullable String deviceId,
            @Nullable Instant sourceAt,
            @Nullable String traceId) {
        jdbc.sql("""
                        insert into nocook.case_events (case_id, kind, server_at, source_at, channel, device_id, metadata, trace_id)
                        values (:caseId, :kind, :serverAt, cast(:sourceAt as timestamptz), :channel, :deviceId,
                                cast(:metadata as jsonb), :traceId)""")
                .param("caseId", caseId)
                .param("kind", fact.kind().name())
                .param("serverAt", CaseRepository.utc(serverAt))
                .param("sourceAt", CaseRepository.utc(sourceAt))
                .param("channel", channel)
                .param("deviceId", deviceId)
                .param("metadata", jsonMapper.writeValueAsString(fact.metadata()))
                .param("traceId", traceId)
                .update();
    }

    /** Fact written by the service itself (channel SYSTEM). */
    public void insertSystem(UUID caseId, Fact fact, Instant serverAt, @Nullable String traceId) {
        insert(caseId, fact, serverAt, CHANNEL_SYSTEM, null, null, traceId);
    }

    public void insertAllSystem(UUID caseId, List<Fact> facts, Instant serverAt, @Nullable String traceId) {
        for (Fact fact : facts) {
            insertSystem(caseId, fact, serverAt, traceId);
        }
    }
}
