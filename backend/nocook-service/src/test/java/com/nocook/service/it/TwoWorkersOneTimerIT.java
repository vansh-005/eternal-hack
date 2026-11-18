package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.service.scheduling.DeadlineWorker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** R2: two workers tick concurrently on 200 due L1 deadlines; each is processed exactly once. */
class TwoWorkersOneTimerIT extends AbstractIT {

    private static final int CASES = 200;

    @Autowired ApplicationContext context;

    @Test
    void two_workers_ticking_concurrently_process_each_of_200_deadlines_once() throws Exception {
        for (int i = 0; i < CASES; i++) {
            openEligibleCase("ord-r2-" + i);
        }
        clock.advance(Duration.ofSeconds(31));
        DeadlineWorker workerA = PendingSupport.newWorker(context, "A");
        DeadlineWorker workerB = PendingSupport.newWorker(context, "B");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> ticks = new ArrayList<>();
        for (DeadlineWorker worker : List.of(workerA, workerB)) {
            ticks.add(pool.submit(() -> {
                startGun.await();
                // batch is 50: loop until nothing is left to claim
                for (int round = 0; round < 10; round++) {
                    worker.tick();
                }
                return null;
            }));
        }
        startGun.countDown();
        for (Future<?> tick : ticks) {
            tick.get();
        }
        pool.shutdown();

        long l1Facts = factCount("L1_TRIGGERED");
        long doneL1 = jdbc.sql("select count(*) from nocook.deadlines where kind = 'L1' and status = 'DONE'")
                .query(Long.class).single();
        long escalated = jdbc.sql("select count(*) from nocook.cases where state = 'ESCALATED_L1'")
                .query(Long.class).single();
        long takeoverCards = jdbc.sql("select count(*) from nocook.outbox where payload ->> 'kind' = 'TAKEOVER'")
                .query(Long.class).single();
        ItSummary.print("R2", Map.of("deadlines", CASES, "l1Facts", l1Facts, "done", doneL1, "takeoverCards", takeoverCards),
                l1Facts == CASES && doneL1 == CASES && takeoverCards == CASES);

        assertThat(l1Facts).isEqualTo(CASES);
        assertThat(doneL1).isEqualTo(CASES);
        assertThat(escalated).isEqualTo(CASES);
        assertThat(takeoverCards).isEqualTo(CASES);
    }
}
