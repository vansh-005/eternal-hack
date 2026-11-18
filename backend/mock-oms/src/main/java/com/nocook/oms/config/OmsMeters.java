package com.nocook.oms.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Meters from OBSERVABILITY.md section 3 for mock-oms. Names are a contract. */
@Component
public class OmsMeters {

    private final MeterRegistry registry;
    private final Counter ordersCreated;
    private final Counter ordersCancelled;
    private final Timer relayPublish;
    private final AtomicLong outboxPending = new AtomicLong();

    public OmsMeters(MeterRegistry registry) {
        this.registry = registry;
        this.ordersCreated = Counter.builder("oms.orders.created").register(registry);
        this.ordersCancelled = Counter.builder("oms.orders.cancelled").register(registry);
        this.relayPublish = Timer.builder("oms.relay.publish").register(registry);
        Gauge.builder("oms.outbox.pending", outboxPending, AtomicLong::get).register(registry);
    }

    public void orderCreated() {
        ordersCreated.increment();
    }

    public void orderCancelled() {
        ordersCancelled.increment();
    }

    public void relayPublished(Duration outboxToConfirm) {
        relayPublish.record(outboxToConfirm);
    }

    public void eventPublished(String routingKey, boolean synthetic) {
        Counter.builder("oms.events.published")
                .tag("routing_key", routingKey)
                .tag("synthetic", Boolean.toString(synthetic))
                .register(registry)
                .increment();
    }

    public void outboxPending(long pendingRows) {
        outboxPending.set(pendingRows);
    }
}
