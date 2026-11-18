package com.nocook.service.scheduling;

import com.nocook.domain.model.AssuranceCase;
import com.nocook.domain.model.DeadlineKind;
import com.nocook.domain.model.Timings;
import com.nocook.domain.oms.OmsView;
import com.nocook.service.application.EscalationService;
import com.nocook.service.config.WorkerProperties;
import com.nocook.service.oms.OmsReadPort;
import com.nocook.service.persistence.CaseRepository;
import com.nocook.service.persistence.CaseRepository.CaseRecord;
import com.nocook.service.persistence.DeadlineRepository;
import com.nocook.service.persistence.DeadlineRepository.ClaimedDeadline;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * BACKEND.md section 3.2. {@link #tick()} claims due deadlines (SKIP LOCKED, 10 s lease), reads OMS OUTSIDE any
 * transaction (CLAUDE.md rule 3; skipped for EXPIRY, aged-out and already-closed cases, which never need it), then lets
 * {@link EscalationService} apply each one in its own transaction. A deadline whose processing throws stays LEASED
 * and is returned by the {@link LeaseReaper} after the lease expires.
 */
@Component
public class DeadlineWorker {

    private static final Logger log = LoggerFactory.getLogger(DeadlineWorker.class);

    private final DeadlineRepository deadlines;
    private final CaseRepository cases;
    private final OmsReadPort omsRead;
    private final EscalationService escalationService;
    private final Clock clock;
    private final Timings timings;
    private final WorkerProperties.Deadlines properties;
    private final String workerId;

    @Autowired
    public DeadlineWorker(
            DeadlineRepository deadlines,
            CaseRepository cases,
            OmsReadPort omsRead,
            EscalationService escalationService,
            Clock clock,
            Timings timings,
            WorkerProperties.Deadlines properties,
            WorkerProperties.Replica replica) {
        this(deadlines, cases, omsRead, escalationService, clock, timings, properties,
                replica.replicaId() + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private DeadlineWorker(
            DeadlineRepository deadlines,
            CaseRepository cases,
            OmsReadPort omsRead,
            EscalationService escalationService,
            Clock clock,
            Timings timings,
            WorkerProperties.Deadlines properties,
            String workerId) {
        this.deadlines = deadlines;
        this.cases = cases;
        this.omsRead = omsRead;
        this.escalationService = escalationService;
        this.clock = clock;
        this.timings = timings;
        this.properties = properties;
        this.workerId = workerId;
    }

    /** Same collaborators under another lease-owner id (tests that simulate competing workers). */
    public DeadlineWorker withWorkerId(String otherWorkerId) {
        return new DeadlineWorker(deadlines, cases, omsRead, escalationService, clock, timings, properties, otherWorkerId);
    }

    public String workerId() {
        return workerId;
    }

    @Scheduled(fixedDelayString = "${nocook.deadlines.tick:250ms}")
    public void scheduledTick() {
        try {
            tick();
        } catch (RuntimeException ex) {
            log.error("deadline tick failed reason={}", ex.toString(), ex);
        }
    }

    /** One claim-and-process pass. Returns the number of deadlines claimed. */
    public int tick() {
        Instant now = clock.instant();
        List<ClaimedDeadline> claimed =
                deadlines.claimDue(now, properties.batch(), workerId, now.plus(properties.lease()));
        for (ClaimedDeadline deadline : claimed) {
            process(deadline);
        }
        return claimed.size();
    }

    private void process(ClaimedDeadline deadline) {
        try {
            OmsView view = readOmsIfNeeded(deadline);
            escalationService.applyDeadline(deadline, view);
        } catch (RuntimeException ex) {
            log.error("deadline processing failed caseId={} kind={} reason={}",
                    deadline.caseId(), deadline.kind(), ex.toString(), ex);
        }
    }

    private OmsView readOmsIfNeeded(ClaimedDeadline deadline) {
        if (deadline.kind() == DeadlineKind.EXPIRY) {
            return new OmsView.NotRead();
        }
        Optional<CaseRecord> record = cases.findById(deadline.caseId());
        if (record.isEmpty()) {
            return new OmsView.NotRead();
        }
        AssuranceCase current = record.get().assuranceCase();
        Instant now = clock.instant();
        boolean closed = !current.state().isOpen();
        boolean agedOut = !now.isBefore(current.cancelledAt().plus(timings.maxAge()));
        if (closed || agedOut) {
            return new OmsView.NotRead();
        }
        return omsRead.getForEscalation(current.orderId());
    }
}
