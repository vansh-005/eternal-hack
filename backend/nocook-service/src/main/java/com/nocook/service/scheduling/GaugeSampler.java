package com.nocook.service.scheduling;

import com.nocook.service.config.RabbitTopologyConfig;
import com.nocook.service.notifications.ListenerControl;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OBSERVABILITY.md section 3.3. Every replica samples; dashboards aggregate with {@code max}. Queue gauges and DB
 * gauges every 5 s, the three safety-invariant gauges (DATA_MODEL.md section 4, nocook schema only) every 15 s. The
 * listener gauge reads the container state directly. A failed sample keeps the previous value and logs at debug.
 */
@Component
public class GaugeSampler {

    private static final Logger log = LoggerFactory.getLogger(GaugeSampler.class);

    private final JdbcClient jdbc;
    private final ObjectProvider<RabbitAdmin> rabbitAdmin;
    private final Clock clock;

    private final AtomicLong cancellationsReady = new AtomicLong();
    private final AtomicLong cancellationsConsumers = new AtomicLong();
    private final AtomicLong dlqReady = new AtomicLong();
    private final AtomicLong dlqConsumers = new AtomicLong();
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong deadlinesOverdue = new AtomicLong();
    private final AtomicLong casesOpen = new AtomicLong();
    private final AtomicLong duplicateCases = new AtomicLong();
    private final AtomicLong maxIvrPerCase = new AtomicLong();
    private final AtomicLong escalationsAfterClose = new AtomicLong();

    public GaugeSampler(
            JdbcClient jdbc,
            ObjectProvider<RabbitAdmin> rabbitAdmin,
            ListenerControl listenerControl,
            MeterRegistry registry,
            Clock clock) {
        this.jdbc = jdbc;
        this.rabbitAdmin = rabbitAdmin;
        this.clock = clock;
        queueGauges(registry, RabbitTopologyConfig.CANCELLATIONS_QUEUE, cancellationsReady, cancellationsConsumers);
        queueGauges(registry, RabbitTopologyConfig.CANCELLATIONS_DLQ, dlqReady, dlqConsumers);
        gauge(registry, "nocook.outbox.pending", outboxPending);
        gauge(registry, "nocook.deadlines.overdue", deadlinesOverdue);
        gauge(registry, "nocook.cases.open", casesOpen);
        gauge(registry, "nocook.invariant.duplicate_cases", duplicateCases);
        gauge(registry, "nocook.invariant.max_ivr_per_case", maxIvrPerCase);
        gauge(registry, "nocook.invariant.escalations_after_close", escalationsAfterClose);
        Gauge.builder("nocook.listener.running", listenerControl, control -> control.isRunning() ? 1 : 0)
                .register(registry);
    }

    private static void gauge(MeterRegistry registry, String name, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::get).register(registry);
    }

    private static void queueGauges(MeterRegistry registry, String queue, AtomicLong ready, AtomicLong consumers) {
        Gauge.builder("nocook.queue.ready", ready, AtomicLong::get).tag("queue", queue).register(registry);
        Gauge.builder("nocook.queue.consumers", consumers, AtomicLong::get).tag("queue", queue).register(registry);
    }

    @Scheduled(fixedDelayString = "${nocook.gauges.tick:5s}", initialDelayString = "2s")
    public void sampleFast() {
        sampleQueues();
        sampleDatabaseGauges();
    }

    @Scheduled(fixedDelayString = "${nocook.gauges.invariant-tick:15s}", initialDelayString = "5s")
    public void sampleInvariants() {
        try {
            duplicateCases.set(jdbc.sql("""
                            select count(*) from (select order_id, order_version from nocook.cases
                                                  group by 1, 2 having count(*) > 1) d""")
                    .query(Long.class).single());
            maxIvrPerCase.set(jdbc.sql("""
                            select coalesce(max(c), 0) from (select case_id, count(*) c from nocook.call_attempts
                                                             group by 1) x""")
                    .query(Long.class).single());
            escalationsAfterClose.set(jdbc.sql("""
                            select count(*) from nocook.case_events e join nocook.cases c on c.id = e.case_id
                            where e.kind in ('L1_TRIGGERED','IVR_ESCALATED','IVR_RESERVED')
                              and c.closed_at is not null and e.server_at > c.closed_at""")
                    .query(Long.class).single());
        } catch (RuntimeException ex) {
            log.debug("invariant gauge sample failed reason={}", ex.toString());
        }
    }

    /** Both samplers once; public for tests. */
    public void sampleAll() {
        sampleFast();
        sampleInvariants();
    }

    private void sampleQueues() {
        RabbitAdmin admin = rabbitAdmin.getIfAvailable();
        if (admin == null) {
            return;
        }
        try {
            copy(admin.getQueueInfo(RabbitTopologyConfig.CANCELLATIONS_QUEUE), cancellationsReady, cancellationsConsumers);
            copy(admin.getQueueInfo(RabbitTopologyConfig.CANCELLATIONS_DLQ), dlqReady, dlqConsumers);
        } catch (RuntimeException ex) {
            log.debug("queue gauge sample failed reason={}", ex.toString());
        }
    }

    private static void copy(QueueInformation info, AtomicLong ready, AtomicLong consumers) {
        if (info != null) {
            ready.set(info.getMessageCount());
            consumers.set(info.getConsumerCount());
        }
    }

    private void sampleDatabaseGauges() {
        try {
            Instant now = clock.instant();
            List<Long> counts = jdbc.sql("""
                            select
                              (select count(*) from nocook.outbox where status in ('PENDING','LEASED')) as outbox_pending,
                              (select count(*) from nocook.deadlines
                                where status = 'PENDING' and due_at < :now - interval '1 second') as deadlines_overdue,
                              (select count(*) from nocook.cases
                                where state in ('AWAITING_ACK','ESCALATED_L1','ESCALATED_IVR')) as cases_open""")
                    .param("now", CaseRepositoryTime.utc(now))
                    .query((rs, rowNum) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3)))
                    .single();
            outboxPending.set(counts.get(0));
            deadlinesOverdue.set(counts.get(1));
            casesOpen.set(counts.get(2));
        } catch (RuntimeException ex) {
            log.debug("db gauge sample failed reason={}", ex.toString());
        }
    }

    /** OffsetDateTime parameter, the same way the repositories bind instants. */
    private static final class CaseRepositoryTime {
        static java.time.OffsetDateTime utc(Instant instant) {
            return instant.atOffset(java.time.ZoneOffset.UTC);
        }
    }
}
