package com.nocook.service.persistence;

import com.nocook.domain.model.DeadlineKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Deadline SQL from DATA_MODEL.md section 3. Every decision query takes {@code now}; none calls now(). */
@Repository
public class DeadlineRepository {

    public record ClaimedDeadline(UUID id, UUID caseId, DeadlineKind kind, Instant dueAt, int attempts) {}

    public record ReapedLease(UUID caseId, DeadlineKind kind, String previousOwner) {}

    private final JdbcClient jdbc;

    public DeadlineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** DOMAIN.md section 5 ScheduleDeadline. Returns false when the (case, kind) row already existed. */
    public boolean insertIfAbsent(UUID caseId, DeadlineKind kind, Instant dueAt) {
        int inserted = jdbc.sql("""
                        insert into nocook.deadlines (case_id, kind, due_at) values (:caseId, :kind, :dueAt)
                        on conflict (case_id, kind) do nothing""")
                .param("caseId", caseId)
                .param("kind", kind.name())
                .param("dueAt", CaseRepository.utc(dueAt))
                .update();
        return inserted == 1;
    }

    /** Claim due deadlines with SKIP LOCKED. */
    public List<ClaimedDeadline> claimDue(Instant now, int batchSize, String workerId, Instant leaseUntil) {
        return jdbc.sql("""
                        with due as (
                            select id
                            from nocook.deadlines
                            where status = 'PENDING' and due_at <= :now
                            order by due_at
                            limit :batchSize
                            for update skip locked
                        )
                        update nocook.deadlines d
                        set status = 'LEASED', lease_owner = :workerId, lease_until = :leaseUntil, attempts = d.attempts + 1
                        from due
                        where d.id = due.id
                        returning d.id, d.case_id, d.kind, d.due_at, d.attempts""")
                .param("now", CaseRepository.utc(now))
                .param("batchSize", batchSize)
                .param("workerId", workerId)
                .param("leaseUntil", CaseRepository.utc(leaseUntil))
                .query((rs, rowNum) -> new ClaimedDeadline(
                        rs.getObject("id", UUID.class),
                        rs.getObject("case_id", UUID.class),
                        DeadlineKind.valueOf(rs.getString("kind")),
                        CaseRepository.instant(rs, "due_at"),
                        rs.getInt("attempts")))
                .list();
    }

    /** The worker finished this claimed deadline. Only a row still LEASED is touched. */
    public void markDone(UUID id, Instant now) {
        jdbc.sql("""
                        update nocook.deadlines
                        set status = 'DONE', done_at = :now, lease_owner = null, lease_until = null
                        where id = :id and status = 'LEASED'""")
                .param("now", CaseRepository.utc(now))
                .param("id", id)
                .update();
    }

    /** DOMAIN.md section 5 RescheduleDeadline. */
    public void reschedule(UUID caseId, DeadlineKind kind, Instant dueAt) {
        jdbc.sql("""
                        update nocook.deadlines
                        set status = 'PENDING', due_at = :dueAt, lease_owner = null, lease_until = null
                        where case_id = :caseId and kind = :kind""")
                .param("dueAt", CaseRepository.utc(dueAt))
                .param("caseId", caseId)
                .param("kind", kind.name())
                .update();
    }

    /** DOMAIN.md section 5 CancelPendingDeadlines (the deadline being processed is already DONE). */
    public int cancelOpenForCase(UUID caseId) {
        return jdbc.sql("""
                        update nocook.deadlines set status = 'CANCELLED', lease_owner = null, lease_until = null
                        where case_id = :caseId and status in ('PENDING','LEASED')""")
                .param("caseId", caseId)
                .update();
    }

    /** D9: the L1 deadline may still be PENDING when IVR fires (L1 was deferred); retire it. */
    public int cancelPendingOfKind(UUID caseId, DeadlineKind kind) {
        return jdbc.sql("""
                        update nocook.deadlines set status = 'CANCELLED', lease_owner = null, lease_until = null
                        where case_id = :caseId and kind = :kind and status = 'PENDING'""")
                .param("caseId", caseId)
                .param("kind", kind.name())
                .update();
    }

    /** Return expired leases to PENDING, reporting the previous owner for the LEASE_TAKEOVER fact. */
    public List<ReapedLease> reapExpiredLeases(Instant now) {
        return jdbc.sql("""
                        with expired as (
                            select id, case_id, kind, lease_owner as previous_owner
                            from nocook.deadlines
                            where status = 'LEASED' and lease_until < :now
                            for update skip locked
                        )
                        update nocook.deadlines d
                        set status = 'PENDING', lease_owner = null, lease_until = null
                        from expired
                        where d.id = expired.id
                        returning expired.case_id, expired.kind, expired.previous_owner""")
                .param("now", CaseRepository.utc(now))
                .query((rs, rowNum) -> new ReapedLease(
                        rs.getObject("case_id", UUID.class),
                        DeadlineKind.valueOf(rs.getString("kind")),
                        rs.getString("previous_owner")))
                .list();
    }
}
