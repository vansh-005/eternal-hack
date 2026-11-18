package com.nocook.service.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read models for the outlet open-cases list and the case timeline (API.md sections 1.3, 1.4). */
@Repository
public class CaseQueryRepository {

    public record OpenCaseRow(
            UUID caseId,
            String orderId,
            String orderRef,
            int orderVersion,
            String state,
            int caseVersion,
            Instant cancelledAt,
            Instant openedAt,
            @Nullable String providerCallId,
            @Nullable String callStatus) {}

    public record CaseHeader(
            UUID caseId,
            String orderId,
            String orderRef,
            String outletId,
            String state,
            int caseVersion,
            Instant cancelledAt,
            Instant openedAt,
            @Nullable Instant closedAt,
            @Nullable String closeReason,
            @Nullable String ackChoice,
            @Nullable String ackChannel,
            @Nullable Instant ackedAt,
            @Nullable String runId) {}

    public record EventRow(
            Instant at, String kind, String channel, @Nullable String deviceId, String metadataJson,
            @Nullable String traceId) {}

    private final JdbcClient jdbc;

    public CaseQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<OpenCaseRow> findOpenByOutlet(String outletId) {
        return jdbc.sql("""
                        select c.id, c.order_id, c.order_ref, c.order_version, c.state, c.version,
                               c.cancelled_at, c.opened_at, ca.provider_call_id, ca.status as call_status
                        from nocook.cases c
                        left join nocook.call_attempts ca
                               on ca.case_id = c.id and ca.provider_call_id is not null
                              and ca.status in ('DIALING','RINGING','ANSWERED')
                        where c.outlet_id = :outletId and c.state in ('AWAITING_ACK','ESCALATED_L1','ESCALATED_IVR')
                        order by c.cancelled_at""")
                .param("outletId", outletId)
                .query((rs, rowNum) -> new OpenCaseRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("order_id"),
                        rs.getString("order_ref"),
                        rs.getInt("order_version"),
                        rs.getString("state"),
                        rs.getInt("version"),
                        CaseRepository.instant(rs, "cancelled_at"),
                        CaseRepository.instant(rs, "opened_at"),
                        rs.getString("provider_call_id"),
                        rs.getString("call_status")))
                .list();
    }

    public Optional<CaseHeader> findHeader(UUID caseId) {
        return jdbc.sql("select * from nocook.cases where id = :id")
                .param("id", caseId)
                .query((rs, rowNum) -> new CaseHeader(
                        rs.getObject("id", UUID.class),
                        rs.getString("order_id"),
                        rs.getString("order_ref"),
                        rs.getString("outlet_id"),
                        rs.getString("state"),
                        rs.getInt("version"),
                        CaseRepository.instant(rs, "cancelled_at"),
                        CaseRepository.instant(rs, "opened_at"),
                        CaseRepository.instant(rs, "closed_at"),
                        rs.getString("close_reason"),
                        rs.getString("ack_choice"),
                        rs.getString("ack_channel"),
                        CaseRepository.instant(rs, "acked_at"),
                        rs.getString("run_id")))
                .optional();
    }

    public List<EventRow> findEvents(UUID caseId) {
        return jdbc.sql("""
                        select server_at, kind, channel, device_id, metadata::text as metadata, trace_id
                        from nocook.case_events where case_id = :id order by server_at, id""")
                .param("id", caseId)
                .query((rs, rowNum) -> new EventRow(
                        CaseRepository.instant(rs, "server_at"),
                        rs.getString("kind"),
                        rs.getString("channel"),
                        rs.getString("device_id"),
                        rs.getString("metadata"),
                        rs.getString("trace_id")))
                .list();
    }
}
