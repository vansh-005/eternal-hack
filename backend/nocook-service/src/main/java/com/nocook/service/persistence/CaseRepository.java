package com.nocook.service.persistence;

import com.nocook.domain.model.AckChannel;
import com.nocook.domain.model.AckChoice;
import com.nocook.domain.model.AssuranceCase;
import com.nocook.domain.model.CaseState;
import com.nocook.domain.model.CloseReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL from DATA_MODEL.md section 3. Never opens a transaction; callers own the boundary. */
@Repository
public class CaseRepository {

    /** Values for a new case row. {@code eventId} must be a UUID string. */
    public record NewCase(
            String orderId,
            int orderVersion,
            String outletId,
            CaseState state,
            @Nullable String notActionableReason,
            Instant cancelledAt,
            Instant openedAt,
            @Nullable Instant closedAt,
            String orderRef,
            String eventId,
            @Nullable String runId,
            @Nullable String traceId) {}

    /** A case row as the domain sees it, plus the columns cards need. */
    public record CaseRecord(
            AssuranceCase assuranceCase,
            String orderRef,
            @Nullable AckChoice ackChoice,
            @Nullable AckChannel ackChannel) {}

    /** Ack columns written by the ACK path (B-05). */
    public record AckUpdate(AckChoice choice, AckChannel channel, String actor, String idempotencyKey, Instant ackedAt) {}

    /** Initial value of {@code cases.version}: API.md examples show the OPEN card as caseVersion 1. */
    public static final int INITIAL_VERSION = 1;

    private final JdbcClient jdbc;

    public CaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent open. Uses a target-less {@code on conflict do nothing} so BOTH the (order_id, order_version)
     * unique constraint and the one-open-case-per-order partial index yield an empty result instead of aborting
     * the transaction. Empty means duplicate.
     */
    public Optional<UUID> insertIfAbsent(NewCase newCase) {
        return jdbc.sql("""
                        insert into nocook.cases (order_id, order_version, outlet_id, state, version, not_actionable_reason,
                                                  cancelled_at, opened_at, closed_at, order_ref, event_id, run_id, trace_id)
                        values (:orderId, :orderVersion, :outletId, :state, :version, :reason,
                                :cancelledAt, :openedAt, cast(:closedAt as timestamptz), :orderRef,
                                cast(:eventId as uuid), :runId, :traceId)
                        on conflict do nothing
                        returning id""")
                .param("orderId", newCase.orderId())
                .param("orderVersion", newCase.orderVersion())
                .param("outletId", newCase.outletId())
                .param("state", newCase.state().name())
                .param("version", INITIAL_VERSION)
                .param("reason", newCase.notActionableReason())
                .param("cancelledAt", utc(newCase.cancelledAt()))
                .param("openedAt", utc(newCase.openedAt()))
                .param("closedAt", utc(newCase.closedAt()))
                .param("orderRef", newCase.orderRef())
                .param("eventId", newCase.eventId())
                .param("runId", newCase.runId())
                .param("traceId", newCase.traceId())
                .query(UUID.class)
                .optional();
    }

    public Optional<UUID> findIdByOrderVersion(String orderId, int orderVersion) {
        return jdbc.sql("select id from nocook.cases where order_id = :orderId and order_version = :orderVersion")
                .param("orderId", orderId)
                .param("orderVersion", orderVersion)
                .query(UUID.class)
                .optional();
    }

    public Optional<UUID> findOpenIdByOrder(String orderId) {
        return jdbc.sql("""
                        select id from nocook.cases
                        where order_id = :orderId and state in ('AWAITING_ACK','ESCALATED_L1','ESCALATED_IVR')""")
                .param("orderId", orderId)
                .query(UUID.class)
                .optional();
    }

    /** Lock the case for a transition (DATA_MODEL.md section 3). */
    public Optional<CaseRecord> lockById(UUID caseId) {
        return jdbc.sql("select * from nocook.cases where id = :caseId for update")
                .param("caseId", caseId)
                .query(CaseRepository::mapCase)
                .optional();
    }

    public Optional<CaseRecord> findById(UUID caseId) {
        return jdbc.sql("select * from nocook.cases where id = :caseId")
                .param("caseId", caseId)
                .query(CaseRepository::mapCase)
                .optional();
    }

    /**
     * Persists a transition: version + 1, new state, optional close and ack columns. Exactly one row must match
     * {@code (id, expectedVersion)}; anything else is a bug and throws.
     */
    public void applyTransition(
            UUID caseId,
            int expectedVersion,
            CaseState newState,
            @Nullable Instant closedAt,
            @Nullable CloseReason closeReason,
            @Nullable AckUpdate ack) {
        int updated = jdbc.sql("""
                        update nocook.cases
                        set state = :state, version = version + 1,
                            closed_at = coalesce(cast(:closedAt as timestamptz), closed_at),
                            close_reason = coalesce(cast(:closeReason as text), close_reason),
                            ack_choice = coalesce(cast(:ackChoice as text), ack_choice),
                            ack_channel = coalesce(cast(:ackChannel as text), ack_channel),
                            ack_actor = coalesce(cast(:ackActor as text), ack_actor),
                            ack_idempotency_key = coalesce(cast(:ackKey as text), ack_idempotency_key),
                            acked_at = coalesce(cast(:ackedAt as timestamptz), acked_at)
                        where id = :caseId and version = :expectedVersion""")
                .param("state", newState.name())
                .param("closedAt", utc(closedAt))
                .param("closeReason", closeReason == null ? null : closeReason.name())
                .param("ackChoice", ack == null ? null : ack.choice().name())
                .param("ackChannel", ack == null ? null : ack.channel().name())
                .param("ackActor", ack == null ? null : ack.actor())
                .param("ackKey", ack == null ? null : ack.idempotencyKey())
                .param("ackedAt", ack == null ? null : utc(ack.ackedAt()))
                .param("caseId", caseId)
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("case version conflict caseId=" + caseId + " expectedVersion=" + expectedVersion);
        }
    }

    static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static CaseRecord mapCase(ResultSet rs, int rowNum) throws SQLException {
        String closeReason = rs.getString("close_reason");
        String ackChoice = rs.getString("ack_choice");
        String ackChannel = rs.getString("ack_channel");
        AssuranceCase assuranceCase = new AssuranceCase(
                rs.getObject("id", UUID.class),
                rs.getString("order_id"),
                rs.getInt("order_version"),
                rs.getString("outlet_id"),
                CaseState.valueOf(rs.getString("state")),
                rs.getInt("version"),
                instant(rs, "cancelled_at"),
                instant(rs, "opened_at"),
                instant(rs, "closed_at"),
                closeReason == null ? null : CloseReason.valueOf(closeReason),
                rs.getString("ack_idempotency_key"));
        return new CaseRecord(
                assuranceCase,
                rs.getString("order_ref"),
                ackChoice == null ? null : AckChoice.valueOf(ackChoice),
                ackChannel == null ? null : AckChannel.valueOf(ackChannel));
    }
}
