package com.nocook.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.service.observability.Spans;
import com.nocook.service.scheduling.GaugeSampler;
import io.micrometer.core.instrument.Gauge;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** B-07: gauges sampled from the DB and broker, counters pre-registered, trace ids and traceparent in the outbox. */
class ObservabilityIT extends B0xBaseIT {

    @Autowired GaugeSampler sampler;
    @Autowired Spans spans;

    private double gauge(String name, String... tags) {
        var search = meters.find(name);
        for (int i = 0; i < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        Gauge found = search.gauge();
        assertThat(found).as(name).isNotNull();
        return found.value();
    }

    @Test
    void gauges_report_db_queue_listener_and_invariant_state() {
        openCase("outlet-it");
        openCase("outlet-it");

        sampler.sampleAll();

        assertThat(gauge("nocook.cases.open")).isEqualTo(2);
        assertThat(gauge("nocook.outbox.pending")).isEqualTo(2); // two OPEN cards not dispatched yet
        assertThat(gauge("nocook.deadlines.overdue")).isZero();
        assertThat(gauge("nocook.queue.ready", "queue", "nocook.cancellations")).isZero();
        assertThat(gauge("nocook.queue.consumers", "queue", "nocook.cancellations")).isPositive();
        assertThat(gauge("nocook.queue.ready", "queue", "nocook.cancellations.dlq")).isZero();
        assertThat(gauge("nocook.listener.running")).isEqualTo(1);
        assertThat(gauge("nocook.invariant.duplicate_cases")).isZero();
        assertThat(gauge("nocook.invariant.max_ivr_per_case")).isZero();
        assertThat(gauge("nocook.invariant.escalations_after_close")).isZero();
    }

    @Test
    void every_counter_series_exists_before_its_first_event() {
        assertThat(meters.find("nocook.chaos").tag("action", "kill-replica").counter()).isNotNull();
        assertThat(meters.find("nocook.ivr.duplicate_prevented").counter()).isNotNull();
        assertThat(meters.find("nocook.lease.takeovers").tag("table", "outbox").counter()).isNotNull();
        assertThat(meters.find("nocook.escalations").tag("kind", "IVR").tag("outcome", "blocked").counter()).isNotNull();
    }

    @Test
    void outbox_rows_carry_the_traceparent_of_their_transaction_and_the_mqtt_payload_does_not() {
        UUID caseId = spans.inSpan("test.cancel", null, Map.of(), () -> openCase("outlet-it"));

        String traceparent = jdbc.sql("select payload ->> 'traceparent' from nocook.outbox where case_id = :id")
                .param("id", caseId).query(String.class).single();
        assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
        String caseTraceId = jdbc.sql("select trace_id from nocook.cases where id = :id")
                .param("id", caseId).query(String.class).single();
        assertThat(traceparent).contains(caseTraceId);

        dispatcher.tick();

        assertThat(mqtt.cards()).hasSize(1);
        assertThat(mqtt.cards().get(0).json().has("traceparent")).isFalse();
        String dispatchTrace = jdbc.sql("select trace_id from nocook.case_events"
                        + " where case_id = :id and kind = 'CARD_DISPATCHED'")
                .param("id", caseId).query(String.class).single();
        assertThat(dispatchTrace).isEqualTo(caseTraceId);
    }
}
