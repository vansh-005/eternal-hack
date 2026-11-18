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

    /** Event whose kind is not a domain FactKind (e.g. DTMF_IGNORED). */
    public void insertRawKind(
            UUID caseId, String kind, Instant serverAt, String channel, @Nullable String deviceId,
            java.util.Map<String, String> metadata, @Nullable String traceId) {
        jdbc.sql("""
                        insert into nocook.case_events (case_id, kind, server_at, channel, device_id, metadata, trace_id)
                        values (:caseId, :kind, :serverAt, :channel, :deviceId, cast(:metadata as jsonb), :traceId)""")
                .param("caseId", caseId)
                .param("kind", kind)
                .param("serverAt", CaseRepository.utc(serverAt))
                .param("channel", channel)
                .param("deviceId", deviceId)
                .param("metadata", jsonMapper.writeValueAsString(metadata))
                .param("traceId", traceId)
                .update();
    }

    /**
     * Records a receipt fact unless one with the same (case, device, kind, messageId) exists. Callers hold an advisory
     * lock on that key, so check-then-insert is race free.
     */
    public boolean insertReceiptIfAbsent(
            UUID caseId, Fact fact, String deviceId, String messageId, Instant serverAt,
            @Nullable Instant sourceAt, @Nullable String traceId) {
        Integer existing = jdbc.sql("""
                        select count(*) from nocook.case_events
                        where case_id = :caseId and kind = :kind and device_id = :deviceId
                          and metadata ->> 'messageId' = :messageId""")
                .param("caseId", caseId)
                .param("kind", fact.kind().name())
                .param("deviceId", deviceId)
                .param("messageId", messageId)
                .query(Integer.class)
                .single();
        if (existing > 0) {
            return false;
        }
        insert(caseId, fact, serverAt, "TABLET", deviceId, sourceAt, traceId);
        return true;
    }

    /** Receipt facts of this kind already recorded for (case, device), across messageIds. */
    public int countForDevice(UUID caseId, String kind, String deviceId) {
        return jdbc.sql("select count(*) from nocook.case_events where case_id = :c and kind = :k and device_id = :d")
                .param("c", caseId)
                .param("k", kind)
                .param("d", deviceId)
                .query(Integer.class)
                .single();
    }
}
