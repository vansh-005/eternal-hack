package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.domain.event.CaseEvent.MerchantAck;
import com.nocook.domain.model.AckChannel;
import com.nocook.domain.model.AckChoice;
import com.nocook.service.application.AckService;
import com.nocook.service.scheduling.DeadlineWorker;
import com.nocook.service.scheduling.OutboxDispatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * R3: 1,000 cases reach ivrAt; ACKs for half of them and the worker tick fire together.
 * Ordering is decided by the database commit timestamps (container runs with track_commit_timestamp=on).
 */
class AckVsIvrRaceIT extends AbstractIT {

    private static final int CASES = 1000;

    @Autowired AckService ackService;
    @Autowired DeadlineWorker deadlineWorker;
    @Autowired OutboxDispatcher dispatcher;

    @Test
    void ack_and_ivr_tick_racing_never_dial_a_case_that_was_acknowledged_first() throws Exception {
        List<UUID> caseIds = new ArrayList<>();
        for (int i = 0; i < CASES; i++) {
            caseIds.add(openEligibleCase("ord-r3-" + i));
        }
        clock.advance(Duration.ofSeconds(76));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> work = new ArrayList<>();
        for (int i = 0; i < CASES; i += 2) {
            UUID caseId = caseIds.get(i);
            work.add(pool.submit(() -> {
                startGun.await();
                ackService.ack(caseId, "outlet-it",
                        new MerchantAck(AckChoice.STOPPED, AckChannel.TABLET, "tab-1", "k-" + caseId), null);
                return null;
            }));
        }
        for (int t = 0; t < 4; t++) {
            work.add(pool.submit(() -> {
                startGun.await();
                for (int round = 0; round < 25; round++) {
                    deadlineWorker.tick();
                }
                return null;
            }));
        }
        startGun.countDown();
        for (Future<?> item : work) {
            item.get();
        }
        pool.shutdown();
        for (int round = 0; round < 25; round++) {
            dispatcher.tick();
        }

        long maxAttempts = jdbc.sql("select coalesce(max(c), 0) from (select count(*) c from nocook.call_attempts"
                + " group by case_id) t").query(Long.class).single();
        // ACK committed first, yet an IVR was reserved afterwards: must be zero.
        long ackedThenReserved = jdbc.sql("""
                select count(*) from nocook.case_events ack
                join nocook.case_events ivr on ivr.case_id = ack.case_id and ivr.kind = 'IVR_RESERVED'
                where ack.kind = 'TABLET_ACKNOWLEDGED'
                  and pg_xact_commit_timestamp(ivr.xmin) > pg_xact_commit_timestamp(ack.xmin)""")
                .query(Long.class).single();
        long dialedAckedFirst = jdbc.sql("""
                select count(*) from nocook.call_attempts ca
                join nocook.case_events ack on ack.case_id = ca.case_id and ack.kind = 'TABLET_ACKNOWLEDGED'
                where ca.status <> 'CANCELLED'
                  and pg_xact_commit_timestamp(ca.xmin) > pg_xact_commit_timestamp(ack.xmin)""")
                .query(Long.class).single();
        long reservedCalls = count("nocook.call_attempts");
        long acked = jdbc.sql("select count(*) from nocook.cases where state = 'ACKNOWLEDGED'").query(Long.class).single();
        ItSummary.print("R3", Map.of("cases", CASES, "acked", acked, "maxIvrPerCase", maxAttempts, "callAttempts", reservedCalls,
                "ackedThenReserved", ackedThenReserved, "dialedAfterAck", dialedAckedFirst),
                maxAttempts <= 1 && ackedThenReserved == 0 && dialedAckedFirst == 0);

        assertThat(reservedCalls).as("the IVR side of the race really ran").isGreaterThan(0);
        assertThat(maxAttempts).isLessThanOrEqualTo(1);
        assertThat(ackedThenReserved).isZero();
        assertThat(dialedAckedFirst).isZero();
    }
}
