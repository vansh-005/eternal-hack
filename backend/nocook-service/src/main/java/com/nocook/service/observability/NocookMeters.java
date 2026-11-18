package com.nocook.service.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
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

    private static Duration nonNegative(Duration elapsed) {
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }
}
