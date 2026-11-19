package com.nocook.service.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read models for the ops screens: newest-first case pages and the KPI counts of {@code /api/ops/summary}. */
@Repository
public class OpsQueryRepository {

    public record OpsCaseRow(
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
            @Nullable String runId) {}

    public record SummaryCounts(
            long openCases,
            long openedLast5m,
            long ackedLast5m,
            long expiredLast5m,
            long outboxPending,
            long deadlinesOverdue) {}

    /** A paging cursor: rows strictly older than this (cancelled_at, id) pair. */
    public record Cursor(Instant cancelledAt, @Nullable UUID caseId) {}

    private final JdbcClient jdbc;

    public OpsQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Cursor> cursorOf(UUID caseId) {
        return jdbc.sql("select cancelled_at, id from nocook.cases where id = :id")
                .param("id", caseId)
                .query((rs, rowNum) -> new Cursor(CaseRepository.instant(rs, "cancelled_at"), rs.getObject("id", UUID.class)))
                .optional();
    }

    /** Newest first by cancelled_at then id. {@code state} and {@code outletId} are optional filters. */
    public List<OpsCaseRow> findCases(@Nullable String state, @Nullable String outletId, int limit, @Nullable Cursor before) {
        StringBuilder sql = new StringBuilder("select * from nocook.cases where true");
        if (state != null) {
            sql.append(" and state = :state");
        }
        if (outletId != null) {
            sql.append(" and outlet_id = :outletId");
        }
        if (before != null && before.caseId() != null) {
            sql.append(" and (cancelled_at, id) < (:beforeAt, :beforeId)");
        } else if (before != null) {
            sql.append(" and cancelled_at < :beforeAt");
        }
        sql.append(" order by cancelled_at desc, id desc limit :limit");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("limit", limit);
        if (state != null) {
            statement = statement.param("state", state);
        }
        if (outletId != null) {
            statement = statement.param("outletId", outletId);
        }
        if (before != null) {
            statement = statement.param("beforeAt", CaseRepository.utc(before.cancelledAt()));
            if (before.caseId() != null) {
                statement = statement.param("beforeId", before.caseId());
            }
        }
        return statement
                .query((rs, rowNum) -> new OpsCaseRow(
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
                        rs.getString("run_id")))
                .list();
    }

    public SummaryCounts summaryCounts(Instant now) {
        Instant since = now.minusSeconds(300);
        return jdbc.sql("""
                        select
                          (select count(*) from nocook.cases
                            where state in ('AWAITING_ACK','ESCALATED_L1','ESCALATED_IVR')) as open_cases,
                          (select count(*) from nocook.cases where opened_at >= :since) as opened_5m,
                          (select count(*) from nocook.cases
                            where state = 'ACKNOWLEDGED' and closed_at >= :since) as acked_5m,
                          (select count(*) from nocook.cases
                            where state = 'EXPIRED' and closed_at >= :since) as expired_5m,
                          (select count(*) from nocook.outbox where status in ('PENDING','LEASED')) as outbox_pending,
                          (select count(*) from nocook.deadlines
                            where status = 'PENDING' and due_at < :now - interval '1 second') as deadlines_overdue""")
                .param("since", CaseRepository.utc(since))
                .param("now", CaseRepository.utc(now))
                .query((rs, rowNum) -> new SummaryCounts(
                        rs.getLong("open_cases"),
                        rs.getLong("opened_5m"),
                        rs.getLong("acked_5m"),
                        rs.getLong("expired_5m"),
                        rs.getLong("outbox_pending"),
                        rs.getLong("deadlines_overdue")))
                .single();
    }
}
