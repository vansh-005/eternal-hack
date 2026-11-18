package com.nocook.oms.firehose;

import com.nocook.oms.config.FirehoseRabbitConfig.FirehoseRabbit;
import com.nocook.oms.config.RabbitTopologyConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Synthetic non-cancel order events straight to the order.events exchange (BACKEND.md section 4).
 * eps is split over max(4, eps/2000) virtual threads; each thread follows its own fixed-rate schedule in small
 * ticks and never bursts to catch up (a thread that falls behind skips the missed ticks).
 */
@Service
public class FirehoseService implements DisposableBean {

    public static final String[] ROUTING_KEYS = {
        "order.placed", "order.accepted", "order.stage_changed", "order.ready",
        "order.picked_up", "order.delivered", "order.reinstated"
    };
    static final long TICK_NANOS = 20_000_000L;
    static final long MAX_LAG_NANOS = 100_000_000L;
    static final int OUTLETS = 500;

    private static final Logger log = LoggerFactory.getLogger(FirehoseService.class);

    public record Status(boolean running, long eventsPerSecond, double achievedPerSecond, long published) {}

    private record Sample(long nanos, long total) {}

    private final RabbitTemplate template;
    private final Counter[] counters = new Counter[ROUTING_KEYS.length];
    private final AtomicLong total = new AtomicLong();
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final Object lifecycleLock = new Object();
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;
    private volatile long targetEps;
    private volatile long runStartTotal;
    private volatile long runStartNanos;

    public FirehoseService(FirehoseRabbit firehoseRabbit, MeterRegistry registry) {
        this.template = firehoseRabbit.template();
        for (int i = 0; i < ROUTING_KEYS.length; i++) {
            counters[i] = Counter.builder("oms.events.published")
                    .tag("routing_key", ROUTING_KEYS[i])
                    .tag("synthetic", "true")
                    .register(registry);
        }
        Gauge.builder("oms.firehose.achieved", this, svc -> svc.achievedPerSecond(5)).register(registry);
    }

    public Status start(long eventsPerSecond) {
        synchronized (lifecycleLock) {
            stopWorkers();
            targetEps = eventsPerSecond;
            runStartTotal = total.get();
            runStartNanos = System.nanoTime();
            synchronized (samples) {
                samples.addLast(new Sample(runStartNanos, runStartTotal));
            }
            running = true;
            int threads = (int) Math.max(4, eventsPerSecond / 2000);
            long baseRate = eventsPerSecond / threads;
            long remainder = eventsPerSecond % threads;
            long start = System.nanoTime();
            for (int i = 0; i < threads; i++) {
                long rate = baseRate + (i < remainder ? 1 : 0);
                long firstTick = start + i * TICK_NANOS / threads;
                workers.add(Thread.ofVirtual().name("firehose-" + i).start(() -> runWorker(rate, firstTick)));
            }
            log.info("firehose started eventsPerSecond={} threads={}", eventsPerSecond, threads);
            return status();
        }
    }

    public Status stop() {
        synchronized (lifecycleLock) {
            long published = total.get() - runStartTotal;
            long eps = targetEps;
            stopWorkers();
            log.info("firehose stopped published={}", published);
            return new Status(false, eps, achievedPerSecond(10), published);
        }
    }

    public Status status() {
        return new Status(running, targetEps, achievedPerSecond(10), total.get() - runStartTotal);
    }

    private void stopWorkers() {
        running = false;
        for (Thread worker : workers) {
            worker.interrupt();
        }
        for (Thread worker : workers) {
            try {
                worker.join(3000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
    }

    private void runWorker(long ratePerSecond, long firstTickNanos) {
        long[] perKey = new long[ROUTING_KEYS.length];
        double perTick = ratePerSecond * (TICK_NANOS / 1_000_000_000.0);
        double carry = 0;
        long next = firstTickNanos;
        while (running && !Thread.currentThread().isInterrupted()) {
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
            carry += perTick;
            int batch = (int) carry;
            carry -= batch;
            if (batch > 0) {
                try {
                    sendBatch(batch, perKey);
                } catch (RuntimeException ex) {
                    if (!running) {
                        break;
                    }
                    log.warn("firehose batch failed reason={}", ex.toString());
                    LockSupport.parkNanos(200_000_000L);
                }
            }
            next += TICK_NANOS;
            if (System.nanoTime() - next > MAX_LAG_NANOS) {
                next = System.nanoTime(); // drop missed ticks: no catch-up burst
            }
        }
    }

    private void sendBatch(int batch, long[] perKey) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        try {
            template.invoke(ops -> {
                for (int i = 0; i < batch; i++) {
                    int keyIndex = random.nextInt(ROUTING_KEYS.length);
                    ops.send(RabbitTopologyConfig.EVENTS_EXCHANGE, ROUTING_KEYS[keyIndex], buildMessage(random, keyIndex));
                    perKey[keyIndex]++;
                }
                return null;
            });
        } finally {
            flush(perKey);
        }
    }

    /** Batched meter update: one counter increment per key per tick instead of one per message. */
    private void flush(long[] perKey) {
        long sent = 0;
        for (int k = 0; k < perKey.length; k++) {
            if (perKey[k] != 0) {
                counters[k].increment(perKey[k]);
                sent += perKey[k];
                perKey[k] = 0;
            }
        }
        total.addAndGet(sent);
    }

    static Message buildMessage(ThreadLocalRandom random, int keyIndex) {
        String eventId = new UUID(random.nextLong(), random.nextLong()).toString();
        int orderNo = random.nextInt(100_000_000);
        String outlet = "outlet-load-" + String.format("%04d", 1 + random.nextInt(OUTLETS));
        StringBuilder sb = new StringBuilder(340)
                .append("{\"schemaVersion\":1,\"eventId\":\"").append(eventId)
                .append("\",\"type\":\"").append(ROUTING_KEYS[keyIndex])
                .append("\",\"orderId\":\"ord-load-").append(orderNo)
                .append("\",\"outletId\":\"").append(outlet)
                .append("\",\"orderVersion\":2,\"occurredAt\":\"").append(Instant.now())
                .append("\",\"initiatedBy\":null,\"runId\":null,\"synthetic\":true,\"pad\":\"");
        while (sb.length() < 298) {
            sb.append('x');
        }
        sb.append("\"}");
        return MessageBuilder.withBody(sb.toString().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(eventId)
                .setHeader("x-synthetic", "true")
                .build();
    }

    @Scheduled(fixedRate = 1000)
    void sample() {
        synchronized (samples) {
            long now = System.nanoTime();
            samples.addLast(new Sample(now, total.get()));
            while (samples.size() > 1 && now - samples.peekFirst().nanos() > 12_000_000_000L) {
                samples.pollFirst();
            }
        }
    }

    /** Events per second over roughly the last windowSeconds, from the 1 s samples plus the live total. */
    double achievedPerSecond(int windowSeconds) {
        long now = System.nanoTime();
        long current = total.get();
        Sample base = null;
        synchronized (samples) {
            for (Sample s : samples) {
                boolean inRun = !running || s.nanos() >= runStartNanos; // a young run must not average in idle time
                if (inRun && now - s.nanos() <= windowSeconds * 1_000_000_000L) {
                    base = s;
                    break;
                }
            }
        }
        if (base == null) {
            return 0;
        }
        double seconds = (now - base.nanos()) / 1e9;
        return seconds < 0.5 ? 0 : (current - base.total()) / seconds;
    }

    @Override
    public void destroy() {
        synchronized (lifecycleLock) {
            stopWorkers();
        }
    }
}
