package com.nocook.service.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code nocook.call_attempts}: UNIQUE(case_id) is invariant I3. Never opens a transaction. */
@Repository
public class CallAttemptRepository {

    public record CallAttempt(
            UUID id, UUID caseId, @Nullable String providerCallId, String status, @Nullable String outcomeDigit) {

        public boolean isTerminal() {
            return CallAttemptRepository.isTerminal(status);
        }
    }

    private final JdbcClient jdbc;

    public CallAttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static boolean isTerminal(String status) {
        return switch (status) {
            case "COMPLETED", "NO_ANSWER", "BUSY", "FAILED", "CANCELLED", "UNKNOWN" -> true;
            default -> false;
        };
    }

    /** Position in the forward-only status ladder; terminal statuses share the top rank. */
    public static int rank(String status) {
        return switch (status) {
            case "RESERVED" -> 0;
            case "DIALING" -> 1;
            case "RINGING" -> 2;
            case "ANSWERED" -> 3;
            default -> 4;
        };
    }

    /** I3: empty result means a call attempt already exists for the case (unique violation absorbed). */
    public Optional<UUID> reserve(UUID caseId, Instant now) {
        return jdbc.sql("""
                        insert into nocook.call_attempts (case_id, status, reserved_at)
                        values (:caseId, 'RESERVED', :now)
                        on conflict (case_id) do nothing
                        returning id""")
                .param("caseId", caseId)
                .param("now", CaseRepository.utc(now))
                .query(UUID.class)
                .optional();
    }

    /** At-most-once guard for the dispatcher: only one caller moves RESERVED to DIALING. */
    public boolean claimDialing(UUID caseId, Instant now) {
        return jdbc.sql("""
                        update nocook.call_attempts set status = 'DIALING', dialed_at = :now
                        where case_id = :caseId and status = 'RESERVED'""")
                        .param("now", CaseRepository.utc(now))
                        .param("caseId", caseId)
                        .update()
                == 1;
    }

    public void setProviderCallId(UUID caseId, String providerCallId) {
        jdbc.sql("update nocook.call_attempts set provider_call_id = :pid where case_id = :caseId")
                .param("pid", providerCallId)
                .param("caseId", caseId)
                .update();
    }

    /** Gateway outcome unknown: UNKNOWN beats a duplicate call, so this is never retried. */
    public void markUnknown(UUID caseId, Instant now) {
        jdbc.sql("""
                        update nocook.call_attempts set status = 'UNKNOWN', ended_at = :now
                        where case_id = :caseId and status = 'DIALING'""")
                .param("now", CaseRepository.utc(now))
                .param("caseId", caseId)
                .update();
    }

    /** Case closed before the dial: RESERVED to CANCELLED. Returns false if the attempt had moved on. */
    public boolean markCancelled(UUID caseId, Instant now) {
        return jdbc.sql("""
                        update nocook.call_attempts set status = 'CANCELLED', ended_at = :now
                        where case_id = :caseId and status = 'RESERVED'""")
                        .param("now", CaseRepository.utc(now))
                        .param("caseId", caseId)
                        .update()
                == 1;
    }

    public Optional<CallAttempt> findByCase(UUID caseId) {
        return jdbc.sql("select * from nocook.call_attempts where case_id = :caseId")
                .param("caseId", caseId)
                .query(CallAttemptRepository::map)
                .optional();
    }

    public Optional<CallAttempt> findByProviderCallId(String providerCallId, boolean forUpdate) {
        String sql = "select * from nocook.call_attempts where provider_call_id = :pid" + (forUpdate ? " for update" : "");
        return jdbc.sql(sql).param("pid", providerCallId).query(CallAttemptRepository::map).optional();
    }

    public void updateStatus(UUID id, String status, @Nullable Instant answeredAt, @Nullable Instant endedAt) {
        jdbc.sql("""
                        update nocook.call_attempts
                        set status = :status,
                            answered_at = coalesce(cast(:answeredAt as timestamptz), answered_at),
                            ended_at = coalesce(cast(:endedAt as timestamptz), ended_at)
                        where id = :id""")
                .param("status", status)
                .param("answeredAt", CaseRepository.utc(answeredAt))
                .param("endedAt", CaseRepository.utc(endedAt))
                .param("id", id)
                .update();
    }

    public void setOutcomeDigit(UUID id, String digit) {
        jdbc.sql("update nocook.call_attempts set outcome_digit = :digit where id = :id")
                .param("digit", digit)
                .param("id", id)
                .update();
    }

    private static CallAttempt map(ResultSet rs, int rowNum) throws SQLException {
        return new CallAttempt(
                rs.getObject("id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getString("provider_call_id"),
                rs.getString("status"),
                rs.getString("outcome_digit"));
    }
}
