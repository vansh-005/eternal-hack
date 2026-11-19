package com.nocook.service.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Meters from docs/OBSERVABILITY.md section 3, created through the registry on use (Micrometer caches by name+tags).
 * B-02 owns only the ingestion meters below; B-07 extends this class with the rest. Names are a contract.
 */
@Component
public class NocookMeters {

    private final MeterRegistry registry;

    public NocookMeters(MeterRegistry registry) {
        this.registry = registry;
        preRegisterCounters();
    }

    /**
     * Creates every counter series at zero so dashboards and the metric-name lock see them before the first event
     * (a Micrometer counter that was never touched would not exist in Prometheus at all).
     */
    private void preRegisterCounters() {
        for (String outcome : List.of("opened", "not_actionable", "duplicate", "oms_unavailable", "invalid")) {
            registry.counter("nocook.events.consumed", "outcome", outcome);
        }
        registry.counter("nocook.cases.opened");
        for (String state : List.of("ACKNOWLEDGED", "SUPERSEDED", "EXPIRED")) {
            registry.counter("nocook.cases.closed", "state", state);
        }
        for (String code : List.of("ALREADY_ACKNOWLEDGED", "CASE_CLOSED", "NOT_ACTIONABLE")) {
            registry.counter("nocook.acks.rejected", "code", code);
        }
        for (String kind : List.of("L1", "IVR", "EXPIRY")) {
            for (String outcome : List.of("fired", "blocked", "deferred", "noop")) {
                registry.counter("nocook.escalations", "kind", kind, "outcome", outcome);
            }
        }
        registry.counter("nocook.ivr.reserved");
        registry.counter("nocook.ivr.duplicate_prevented");
        registry.counter("nocook.lease.takeovers", "table", "deadlines");
        registry.counter("nocook.lease.takeovers", "table", "outbox");
        for (String kind : List.of("status", "dtmf")) {
            for (String result : List.of("ok", "duplicate", "bad_signature")) {
                registry.counter("nocook.webhooks", "kind", kind, "result", result);
            }
        }
        for (String action : CHAOS_ACTIONS) {
            registry.counter("nocook.chaos", "action", action);
        }
    }

    public static final List<String> CHAOS_ACTIONS =
            List.of("db-latency", "oms-down", "listener", "kill-replica", "duplicate-storm", "reset");

    /** One chaos endpoint call. */
    public void chaos(String action) {
        registry.counter("nocook.chaos", "action", action).increment();
    }

    /** outcome = opened | not_actionable | duplicate | oms_unavailable | invalid */
    public void eventConsumed(String outcome) {
        registry.counter("nocook.events.consumed", "outcome", outcome).increment();
    }

    /** An eligible case was committed. */
    public void caseOpened() {
        registry.counter("nocook.cases.opened").increment();
    }

    /** occurredAt (OMS commit) to case transaction committed. */
    public void caseOpenLatency(Duration elapsed) {
        registry.timer("nocook.case.open").record(nonNegative(elapsed));
    }

    /** x-published-at header to listener start. */
    public void consumeLag(Duration elapsed) {
        registry.timer("nocook.consume.lag").record(nonNegative(elapsed));
    }

    /** outcome = ok | timeout | error; path = open | escalation. */
    public void omsRead(String outcome, String path, Duration elapsed) {
        registry.timer("nocook.oms.read", "outcome", outcome, "path", path).record(nonNegative(elapsed));
    }

    /** outcome = fired | blocked | deferred | noop */
    public void escalation(String kind, String outcome) {
        registry.counter("nocook.escalations", "kind", kind, "outcome", outcome).increment();
    }

    public void deadlineLateness(String kind, Duration elapsed) {
        registry.timer("nocook.deadline.lateness", "kind", kind).record(nonNegative(elapsed));
    }

    /** state = ACKNOWLEDGED | SUPERSEDED | EXPIRED */
    public void caseClosed(String state) {
        registry.counter("nocook.cases.closed", "state", state).increment();
    }

    /** table = deadlines | outbox */
    public void leaseTakeover(String table) {
        registry.counter("nocook.lease.takeovers", "table", table).increment();
    }

    public void ack(String channel, String choice) {
        registry.counter("nocook.acks", "channel", channel, "choice", choice).increment();
    }

    /** code = ALREADY_ACKNOWLEDGED | CASE_CLOSED | NOT_ACTIONABLE */
    public void ackRejected(String code) {
        registry.counter("nocook.acks.rejected", "code", code).increment();
    }

    public void ivrReserved() {
        registry.counter("nocook.ivr.reserved").increment();
    }

    public void ivrDuplicatePrevented() {
        registry.counter("nocook.ivr.duplicate_prevented").increment();
    }

    /** kind = status | dtmf; result = ok | duplicate | bad_signature */
    public void webhook(String kind, String result) {
        registry.counter("nocook.webhooks", "kind", kind, "result", result).increment();
    }

    public void call(String status) {
        registry.counter("nocook.calls", "status", status).increment();
    }

    /** kind = DELIVERED | RENDERED | DISMISSED; client = tablet | swarm */
    public void receipt(String kind, String client) {
        registry.counter("nocook.receipts", "kind", kind, "client", client).increment();
    }

    public void cardDelivery(String client, Duration elapsed) {
        registry.timer("nocook.card.delivery", "client", client).record(nonNegative(elapsed));
    }

    public void cardRender(String client, Duration elapsed) {
        registry.timer("nocook.card.render", "client", client).record(nonNegative(elapsed));
    }

    public void ackFanout(String client, Duration elapsed) {
        registry.timer("nocook.ack.fanout", "client", client).record(nonNegative(elapsed));
    }

    /** Outbox created_at to SENT; type = MQTT_CARD | MQTT_CALL | PLACE_CALL */
    public void outboxDispatch(String type, Duration elapsed) {
        registry.timer("nocook.outbox.dispatch", "type", type).record(nonNegative(elapsed));
    }

    private static Duration nonNegative(Duration elapsed) {
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }
}
