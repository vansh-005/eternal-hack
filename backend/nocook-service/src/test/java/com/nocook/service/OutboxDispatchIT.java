package com.nocook.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** B-03: outbox claim, QoS 1 publish, SENT, retry with backoff, CARD_DISPATCHED, receipts. */
class OutboxDispatchIT extends B0xBaseIT {

    @Test
    void pending_card_is_published_once_and_marked_sent() {
        UUID caseId = openCase("outlet-it");

        int dispatched = dispatcher.tick();

        assertThat(dispatched).isEqualTo(1);
        assertThat(mqtt.cards()).hasSize(1);
        Published card = mqtt.cards().get(0);
        assertThat(card.outletId()).isEqualTo("outlet-it");
        assertThat(card.json().get("kind").asString()).isEqualTo("OPEN");
        assertThat(card.json().get("messageId").asString()).isEqualTo("card:" + caseId + ":OPEN:1");
        assertThat(card.json().get("sentAt").asString()).isEqualTo("2026-11-21T14:00:00.000Z");
        assertThat(outboxRows(caseId)).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "SENT"));
        assertThat(countFacts(caseId, "CARD_DISPATCHED")).isEqualTo(1);

        assertThat(dispatcher.tick()).isZero();
        assertThat(mqtt.cards()).hasSize(1);
    }

    @Test
    void publish_failure_is_retried_with_500ms_times_attempts_backoff() {
        UUID caseId = openCase("outlet-it");
        mqtt.failuresRemaining.set(1);

        dispatcher.tick();

        Map<String, Object> row = outboxRows(caseId).get(0);
        assertThat(row).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat(mqtt.cards()).isEmpty();
        assertThat(countFacts(caseId, "CARD_DISPATCHED")).isZero();

        // not yet due
        clock.advance(Duration.ofMillis(499));
        assertThat(dispatcher.tick()).isZero();
        clock.advance(Duration.ofMillis(1));
        assertThat(dispatcher.tick()).isEqualTo(1);

        assertThat(outboxRows(caseId).get(0)).containsEntry("status", "SENT").containsEntry("attempts", 2);
        assertThat(mqtt.cards()).hasSize(1);
        assertThat(countFacts(caseId, "CARD_DISPATCHED")).isEqualTo(1);
    }

    @Test
    void row_is_failed_after_ten_attempts() {
        UUID caseId = openCase("outlet-it");
        mqtt.failAll = true;

        for (int attempt = 1; attempt <= 10; attempt++) {
            dispatcher.tick();
            clock.advance(Duration.ofSeconds(10));
        }

        assertThat(outboxRows(caseId).get(0)).containsEntry("status", "FAILED").containsEntry("attempts", 10);
        mqtt.failAll = false;
        assertThat(dispatcher.tick()).isZero();
        assertThat(mqtt.cards()).isEmpty();
    }

    @Test
    void cards_of_one_case_are_published_in_creation_order() {
        UUID caseId = openCase("outlet-it", Duration.ofSeconds(31));
        // L1 is already due: escalate, then the OPEN and TAKEOVER cards are both pending
        deadlineWorker.tick();

        dispatcher.tick();

        assertThat(mqtt.cards()).extracting(p -> p.json().get("kind").asString()).containsExactly("OPEN", "TAKEOVER");
        assertThat(mqtt.cards().get(1).json().get("state").asString()).isEqualTo("ESCALATED_L1");
        assertThat(mqtt.cards().get(1).json().get("caseVersion").asInt()).isEqualTo(2);
        assertThat(caseId).isNotNull();
    }

    @Test
    void receipts_record_first_per_key_and_drive_timers_with_client_tag() {
        UUID caseId = openCase("outlet-it", Duration.ofMillis(100));
        clock.advance(Duration.ofMillis(250));
        String messageId = "card:" + caseId + ":OPEN:1";
        double tabletBefore = timerCount("nocook.card.delivery", "tablet");
        double swarmBefore = timerCount("nocook.card.delivery", "swarm");

        Resp first = receipt(caseId, "DELIVERED", "tab-1", messageId);
        Resp repeat = receipt(caseId, "DELIVERED", "tab-1", messageId);
        Resp swarm = receipt(caseId, "DELIVERED", "swarm-0007", messageId);
        Resp rendered = receipt(caseId, "RENDERED", "tab-1", messageId);

        assertThat(first.status()).isEqualTo(202);
        assertThat(first.body().get("recorded").asBoolean()).isTrue();
        assertThat(repeat.status()).isEqualTo(202);
        assertThat(repeat.body().get("recorded").asBoolean()).isFalse();
        assertThat(swarm.status()).isEqualTo(202);
        assertThat(rendered.status()).isEqualTo(202);
        assertThat(countFacts(caseId, "CARD_DELIVERED")).isEqualTo(2);
        assertThat(countFacts(caseId, "CARD_RENDERED")).isEqualTo(1);
        assertThat(timerCount("nocook.card.delivery", "tablet")).isEqualTo(tabletBefore + 1);
        assertThat(timerCount("nocook.card.delivery", "swarm")).isEqualTo(swarmBefore + 1);
        assertThat(receipt(UUID.randomUUID(), "DELIVERED", "tab-1", "x").status()).isEqualTo(404);
        assertThat(http("POST", "/api/cases/" + caseId + "/receipts", Map.of(),
                        "{\"kind\":\"NOPE\",\"deviceId\":\"d\",\"messageId\":\"m\"}").status())
                .isEqualTo(400);
        // a receipt never changes the case
        assertThat(state(caseId)).isEqualTo("AWAITING_ACK");
    }

    private Resp receipt(UUID caseId, String kind, String deviceId, String messageId) {
        return http("POST", "/api/cases/" + caseId + "/receipts", Map.of(),
                "{\"kind\":\"" + kind + "\",\"deviceId\":\"" + deviceId + "\",\"messageId\":\"" + messageId + "\"}");
    }

    private double timerCount(String name, String client) {
        var timer = meters.find(name).tag("client", client).timer();
        return timer == null ? 0 : timer.count();
    }
}
