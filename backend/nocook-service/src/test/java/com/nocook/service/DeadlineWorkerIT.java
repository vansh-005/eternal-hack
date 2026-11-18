package com.nocook.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.service.persistence.DeadlineRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** B-04: DeadlineWorker.tick() end to end on the real database, plus the lease reaper. */
class DeadlineWorkerIT extends B0xBaseIT {

    @Autowired
    DeadlineRepository deadlines;

    @Test
    void nothing_is_claimed_before_the_deadline_and_l1_fires_at_exactly_30s() {
        UUID caseId = openCase("outlet-it");

        clock.advance(Duration.ofMillis(29_999));
        assertThat(deadlineWorker.tick()).isZero();
        assertThat(state(caseId)).isEqualTo("AWAITING_ACK");

        clock.advance(Duration.ofMillis(1));
        assertThat(deadlineWorker.tick()).isEqualTo(1);

        assertThat(state(caseId)).isEqualTo("ESCALATED_L1");
        assertThat(deadlineStatus(caseId, "L1")).isEqualTo("DONE");
        assertThat(factKinds(caseId)).containsExactly("CASE_OPENED", "L1_TRIGGERED");
        assertThat(outboxRows(caseId)).extracting(r -> r.get("action_key"))
                .contains("card:" + caseId + ":TAKEOVER:2");
        assertThat(counter("nocook.escalations", "kind", "L1", "outcome", "fired")).isGreaterThan(0);
        assertThat(meters.find("nocook.deadline.lateness").tag("kind", "L1").timer()).isNotNull();
    }

    @Test
    void ivr_deadline_reserves_one_call_attempt_and_a_place_call_intent() {
        UUID caseId = openCase("outlet-it");
        clock.advance(Duration.ofSeconds(30));
        deadlineWorker.tick();
        clock.advance(Duration.ofSeconds(45));

        deadlineWorker.tick();

        assertThat(state(caseId)).isEqualTo("ESCALATED_IVR");
        assertThat(factKinds(caseId)).contains("IVR_ESCALATED", "IVR_RESERVED");
        assertThat(jdbc.sql("select status from nocook.call_attempts where case_id = :id").param("id", caseId)
                        .query(String.class).single()).isEqualTo("RESERVED");
        assertThat(outboxRows(caseId)).extracting(r -> r.get("action_key"))
                .contains("ivr:" + caseId);
        assertThat(counter("nocook.ivr.reserved")).isGreaterThan(0);
    }

    @Test
    void ivr_while_l1_is_still_deferred_retires_the_pending_l1_deadline() {
        UUID caseId = openCase("outlet-it");
        clock.advance(Duration.ofSeconds(74));
        oms.down = true;
        deadlineWorker.tick(); // L1 deferred to +76 s
        assertThat(deadlineStatus(caseId, "L1")).isEqualTo("PENDING");
        oms.down = false;
        clock.advance(Duration.ofSeconds(1)); // +75 s: IVR due, L1 (due +76 s) not yet

        deadlineWorker.tick();

        assertThat(state(caseId)).isEqualTo("ESCALATED_IVR");
        assertThat(deadlineStatus(caseId, "L1")).isEqualTo("CANCELLED");
        assertThat(deadlineStatus(caseId, "IVR")).isEqualTo("DONE");
    }

    @Test
    void oms_unavailable_defers_without_escalating_and_escalates_after_recovery() {
        UUID caseId = openCase("outlet-it");
        clock.advance(Duration.ofSeconds(30));
        oms.down = true;

        deadlineWorker.tick();

        assertThat(state(caseId)).isEqualTo("AWAITING_ACK");
        assertThat(deadlineStatus(caseId, "L1")).isEqualTo("PENDING");
        assertThat(factKinds(caseId)).containsExactly("CASE_OPENED", "ESCALATION_DEFERRED");
        assertThat(outboxRows(caseId)).hasSize(1); // only the OPEN card
        assertThat(counter("nocook.escalations", "kind", "L1", "outcome", "deferred")).isGreaterThan(0);

        oms.down = false;
        clock.advance(Duration.ofMillis(1_999));
        assertThat(deadlineWorker.tick()).isZero();
        clock.advance(Duration.ofMillis(1));
        deadlineWorker.tick();
        assertThat(state(caseId)).isEqualTo("ESCALATED_L1");
    }

    @Test
    void oms_showing_ready_supersedes_and_cancels_the_remaining_deadlines() {
        UUID caseId = openCase("outlet-it");
        markReady(orderIdOf(caseId));
        clock.advance(Duration.ofSeconds(30));

        deadlineWorker.tick();

        assertThat(state(caseId)).isEqualTo("SUPERSEDED");
        assertThat(factKinds(caseId)).contains("ESCALATION_BLOCKED", "CASE_SUPERSEDED", "CASE_CLOSED");
        assertThat(deadlineStatus(caseId, "IVR")).isEqualTo("CANCELLED");
        assertThat(deadlineStatus(caseId, "EXPIRY")).isEqualTo("CANCELLED");
        assertThat(outboxRows(caseId)).extracting(r -> r.get("kind")).contains("CLOSE");
        assertThat(counter("nocook.escalations", "kind", "L1", "outcome", "blocked")).isGreaterThan(0);
    }

    @Test
    void expiry_closes_the_case_even_when_oms_is_down_and_later_deadlines_are_inert() {
        UUID caseId = openCase("outlet-it");
        oms.down = true;
        clock.advance(Duration.ofMinutes(10));

        deadlineWorker.tick();
        deadlineWorker.tick();

        assertThat(state(caseId)).isEqualTo("EXPIRED");
        assertThat(factKinds(caseId)).doesNotContain("L1_TRIGGERED", "IVR_ESCALATED");
        assertThat(factKinds(caseId)).contains("CASE_EXPIRED");
        assertThat(outboxRows(caseId)).extracting(r -> r.get("kind")).contains("CLOSE");
        assertThat(jdbc.sql("select count(*) from nocook.deadlines where case_id = :id and status in ('PENDING','LEASED')")
                        .param("id", caseId).query(Long.class).single()).isZero();
    }

    @Test
    void expired_case_with_active_call_queues_a_call_ended_intent() {
        UUID caseId = openCase("outlet-it");
        clock.advance(Duration.ofSeconds(75));
        deadlineWorker.tick();
        assertThat(state(caseId)).isEqualTo("ESCALATED_IVR");
        clock.advance(Duration.ofMinutes(10));

        deadlineWorker.tick();

        assertThat(state(caseId)).isEqualTo("EXPIRED");
        assertThat(outboxRows(caseId)).extracting(r -> r.get("action_key")).contains("callend:" + caseId);
    }

    @Test
    void expired_lease_is_reaped_with_a_takeover_fact_and_processed_by_another_worker() {
        UUID caseId = openCase("outlet-it");
        clock.advance(Duration.ofSeconds(30));
        // worker "A" claims and then never finishes
        assertThat(deadlines.claimDue(clock.instant(), 50, "worker-A", clock.instant().plusSeconds(10))).hasSize(1);
        assertThat(deadlineWorker.tick()).isZero();

        clock.advance(Duration.ofSeconds(11));
        assertThat(reaper.tick()).isEqualTo(1);

        assertThat(countFacts(caseId, "LEASE_TAKEOVER")).isEqualTo(1);
        assertThat(counter("nocook.lease.takeovers", "table", "deadlines")).isGreaterThan(0);
        assertThat(jdbc.sql("select metadata ->> 'previousOwner' from nocook.case_events where kind = 'LEASE_TAKEOVER'")
                        .query(String.class).single()).isEqualTo("worker-A");
        assertThat(deadlineWorker.tick()).isEqualTo(1);
        assertThat(state(caseId)).isEqualTo("ESCALATED_L1");
    }

    @Test
    void two_workers_never_process_the_same_deadline_twice() {
        List<UUID> caseIds = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            caseIds.add(openCase("outlet-it"));
        }
        clock.advance(Duration.ofSeconds(30));
        var workerB = deadlineWorker.withWorkerId("worker-B");

        var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Integer>>();
        futures.add(java.util.concurrent.CompletableFuture.supplyAsync(deadlineWorker::tick));
        futures.add(java.util.concurrent.CompletableFuture.supplyAsync(workerB::tick));
        int claimed = futures.stream().mapToInt(java.util.concurrent.CompletableFuture::join).sum();

        assertThat(claimed).isEqualTo(30);
        assertThat(jdbc.sql("select count(*) from nocook.case_events where kind = 'L1_TRIGGERED'")
                        .query(Long.class).single()).isEqualTo(30L);
        assertThat(caseIds).allSatisfy(id -> assertThat(state(id)).isEqualTo("ESCALATED_L1"));
    }
}
