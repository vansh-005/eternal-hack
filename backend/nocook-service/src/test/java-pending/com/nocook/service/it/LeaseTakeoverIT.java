package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.domain.model.DeadlineKind;
import com.nocook.service.persistence.DeadlineRepository;
import com.nocook.service.scheduling.DeadlineWorker;
import com.nocook.service.scheduling.LeaseReaper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** R4: worker A claims a deadline (lease 10 s) and dies; after +11 s the reaper frees it and worker B processes it. */
class LeaseTakeoverIT extends AbstractIT {

    @Autowired DeadlineRepository deadlineRepository;
    @Autowired LeaseReaper reaper;
    @Autowired ApplicationContext context;

    @Test
    void expired_lease_is_reaped_and_taken_over_by_another_worker() {
        UUID caseId = openEligibleCase("ord-r4");
        clock.advance(Duration.ofSeconds(31));
        double takeoversBefore = counter("nocook.lease.takeovers", "table", "deadlines");

        var claimed = deadlineRepository.claimDue(clock.instant(), 50, "worker-A", clock.instant().plusSeconds(10));
        assertThat(claimed).extracting(DeadlineRepository.ClaimedDeadline::kind).containsExactly(DeadlineKind.L1);
        // worker A "crashes": it never processes the deadline.

        DeadlineWorker workerB = PendingSupport.newWorker(context, "worker-B");
        workerB.tick();
        assertThat(stateOf(caseId)).as("lease still valid, B must not steal it").isEqualTo("AWAITING_ACK");

        clock.advance(Duration.ofSeconds(11));
        reaper.tick();
        workerB.tick();

        double takeovers = counter("nocook.lease.takeovers", "table", "deadlines") - takeoversBefore;
        ItSummary.print("R4", Map.of("state", stateOf(caseId), "takeoverFacts", factCount("LEASE_TAKEOVER"),
                "takeoverCounter", (long) takeovers),
                stateOf(caseId).equals("ESCALATED_L1") && takeovers == 1);

        assertThat(stateOf(caseId)).isEqualTo("ESCALATED_L1");
        assertThat(factKinds(caseId)).contains("LEASE_TAKEOVER", "L1_TRIGGERED");
        assertThat(takeovers).isEqualTo(1);
    }
}
