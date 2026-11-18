package com.nocook.service.scheduling;

import com.nocook.domain.effect.Fact;
import com.nocook.domain.effect.FactKind;
import com.nocook.service.config.TraceIds;
import com.nocook.service.observability.NocookMeters;
import com.nocook.service.persistence.CaseEventRepository;
import com.nocook.service.persistence.DeadlineRepository;
import com.nocook.service.persistence.DeadlineRepository.ReapedLease;
import com.nocook.service.persistence.OutboxRepository;
import com.nocook.service.persistence.OutboxRepository.ReapedOutbox;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Returns expired leases (deadlines and outbox) to PENDING so another worker takes over, and records a
 * {@code LEASE_TAKEOVER} fact per row plus {@code nocook.lease.takeovers}. Reap and facts commit together.
 */
@Component
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final DeadlineRepository deadlines;
    private final OutboxRepository outbox;
    private final CaseEventRepository caseEvents;
    private final NocookMeters meters;
    private final TraceIds traceIds;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public LeaseReaper(
            DeadlineRepository deadlines,
            OutboxRepository outbox,
            CaseEventRepository caseEvents,
            NocookMeters meters,
            TraceIds traceIds,
            Clock clock,
            TransactionTemplate transaction) {
        this.deadlines = deadlines;
        this.outbox = outbox;
        this.caseEvents = caseEvents;
        this.meters = meters;
        this.traceIds = traceIds;
        this.clock = clock;
        this.transaction = transaction;
    }

    @Scheduled(fixedDelayString = "${nocook.reaper.tick:2s}")
    public void scheduledTick() {
        try {
            tick();
        } catch (RuntimeException ex) {
            log.error("lease reaper failed reason={}", ex.toString(), ex);
        }
    }

    /** One pass over both tables. Returns the number of leases taken back. */
    public int tick() {
        Instant now = clock.instant();
        int reaped = transaction.execute(status -> reapDeadlines(now));
        reaped += transaction.execute(status -> reapOutbox(now));
        return reaped;
    }

    private int reapDeadlines(Instant now) {
        List<ReapedLease> reaped = deadlines.reapExpiredLeases(now);
        String traceId = traceIds.current();
        for (ReapedLease lease : reaped) {
            Fact fact = new Fact(
                    FactKind.LEASE_TAKEOVER,
                    Map.of("table", "deadlines", "deadlineKind", lease.kind().name(),
                            "previousOwner", String.valueOf(lease.previousOwner())));
            caseEvents.insertSystem(lease.caseId(), fact, now, traceId);
            meters.leaseTakeover("deadlines");
            log.warn("lease takeover table=deadlines caseId={} kind={} previousOwner={}",
                    lease.caseId(), lease.kind(), lease.previousOwner());
        }
        return reaped.size();
    }

    private int reapOutbox(Instant now) {
        List<ReapedOutbox> reaped = outbox.reapExpiredLeases(now);
        String traceId = traceIds.current();
        for (ReapedOutbox lease : reaped) {
            Fact fact = new Fact(
                    FactKind.LEASE_TAKEOVER,
                    Map.of("table", "outbox", "outboxType", lease.type(),
                            "previousOwner", String.valueOf(lease.previousOwner())));
            caseEvents.insertSystem(lease.caseId(), fact, now, traceId);
            meters.leaseTakeover("outbox");
            log.warn("lease takeover table=outbox caseId={} type={} previousOwner={}",
                    lease.caseId(), lease.type(), lease.previousOwner());
        }
        return reaped.size();
    }
}
