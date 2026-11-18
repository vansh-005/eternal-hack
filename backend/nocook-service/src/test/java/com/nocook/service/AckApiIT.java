package com.nocook.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.domain.model.CancelInitiator;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** B-05: every row of the API.md 1.1 table, plus open-cases and the timeline. */
class AckApiIT extends B0xBaseIT {

    @Test
    void ack_closes_the_case_records_the_fact_and_queues_the_close_card() {
        UUID caseId = openCase("outlet-it");

        Resp response = ack(caseId, "outlet-it", "k1", "STOPPED");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("state").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(response.body().get("choice").asString()).isEqualTo("STOPPED");
        assertThat(response.body().get("channel").asString()).isEqualTo("TABLET");
        assertThat(response.body().get("duplicate").asBoolean()).isFalse();
        assertThat(response.body().get("ackedAt").asString()).isEqualTo("2026-11-21T14:00:00.000Z");
        assertThat(state(caseId)).isEqualTo("ACKNOWLEDGED");
        assertThat(jdbc.sql("select ack_choice || '/' || ack_channel || '/' || ack_actor || '/' || ack_idempotency_key"
                        + " from nocook.cases where id = :id").param("id", caseId).query(String.class).single())
                .isEqualTo("STOPPED/TABLET/tab-1/k1");
        assertThat(factKinds(caseId)).contains("TABLET_ACKNOWLEDGED", "CASE_CLOSED");
        assertThat(jdbc.sql("select device_id from nocook.case_events where kind = 'TABLET_ACKNOWLEDGED'")
                        .query(String.class).single()).isEqualTo("tab-1");
        assertThat(outboxRows(caseId)).extracting(r -> r.get("kind")).contains("OPEN", "CLOSE");
        assertThat(jdbc.sql("select count(*) from nocook.deadlines where case_id = :id and status in ('PENDING','LEASED')")
                        .param("id", caseId).query(Long.class).single()).isZero();
        assertThat(counter("nocook.acks", "channel", "TABLET", "choice", "STOPPED")).isGreaterThan(0);
    }

    @Test
    void ack_is_not_gated_on_the_case_version_the_tablet_saw() {
        UUID caseId = openCase("outlet-it");
        clock.advance(Duration.ofSeconds(30));
        deadlineWorker.tick(); // L1: case version is now 2

        Resp response = http("POST", "/api/cases/" + caseId + "/ack",
                Map.of("X-Outlet-Id", "outlet-it", "X-Device-Id", "tab-1"),
                "{\"choice\":\"NOT_STARTED\",\"idempotencyKey\":\"k1\",\"caseVersionSeen\":1}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(state(caseId)).isEqualTo("ACKNOWLEDGED");
        assertThat(jdbc.sql("select metadata ->> 'caseVersionSeen' from nocook.case_events"
                        + " where kind = 'TABLET_ACKNOWLEDGED'").query(String.class).single()).isEqualTo("1");
    }

    @Test
    void second_ack_with_another_key_is_409_already_acknowledged_with_the_first_choice() {
        UUID caseId = openCase("outlet-it");
        ack(caseId, "outlet-it", "k1", "STOPPED");

        Resp response = ack(caseId, "outlet-it", "k2", "ALREADY_COOKED");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().get("error").asString()).isEqualTo("ALREADY_ACKNOWLEDGED");
        assertThat(response.body().get("ackChoice").asString()).isEqualTo("STOPPED");
        assertThat(response.body().get("ackedAt").asString()).isNotBlank();
        assertThat(response.body().get("caseId").asString()).isEqualTo(caseId.toString());
        assertThat(countFacts(caseId, "LATE_ACK_IGNORED")).isEqualTo(1);
        assertThat(counter("nocook.acks.rejected", "code", "ALREADY_ACKNOWLEDGED")).isGreaterThan(0);
    }

    @Test
    void ack_on_an_expired_case_is_409_case_closed_and_leaves_a_late_ack_fact() {
        UUID caseId = openCase("outlet-it");
        clock.advance(Duration.ofMinutes(10));
        deadlineWorker.tick();
        assertThat(state(caseId)).isEqualTo("EXPIRED");

        Resp response = ack(caseId, "outlet-it", "k1", "STOPPED");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().get("error").asString()).isEqualTo("CASE_CLOSED");
        assertThat(response.body().get("state").asString()).isEqualTo("EXPIRED");
        assertThat(response.body().get("closeReason").asString()).isEqualTo("EXPIRED");
        assertThat(countFacts(caseId, "LATE_ACK_IGNORED")).isEqualTo(1);
        assertThat(state(caseId)).isEqualTo("EXPIRED");
    }

    @Test
    void ack_on_a_not_actionable_case_is_409_not_actionable() {
        var snapshot = cancelledOrder("outlet-it", Duration.ZERO, CancelInitiator.RESTAURANT);
        UUID caseId = ingest(snapshot).caseId();
        assertThat(state(caseId)).isEqualTo("NOT_ACTIONABLE");

        Resp response = ack(caseId, "outlet-it", "k1", "STOPPED");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().get("error").asString()).isEqualTo("NOT_ACTIONABLE");
        assertThat(state(caseId)).isEqualTo("NOT_ACTIONABLE");
    }

    @Test
    void unknown_or_malformed_case_id_is_404() {
        Resp unknown = ack(UUID.randomUUID(), "outlet-it", "k1", "STOPPED");
        Resp malformed = http("POST", "/api/cases/not-a-uuid/ack", Map.of("X-Outlet-Id", "outlet-it"),
                "{\"choice\":\"STOPPED\",\"idempotencyKey\":\"k\"}");

        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body().get("error").asString()).isEqualTo("CASE_NOT_FOUND");
        assertThat(malformed.status()).isEqualTo(404);
        assertThat(malformed.body().get("error").asString()).isEqualTo("CASE_NOT_FOUND");
    }

    @Test
    void wrong_or_missing_outlet_header_is_403_and_nothing_changes() {
        UUID caseId = openCase("outlet-it");

        Resp wrong = ack(caseId, "outlet-other", "k1", "STOPPED");
        Resp missing = http("POST", "/api/cases/" + caseId + "/ack", Map.of(),
                "{\"choice\":\"STOPPED\",\"idempotencyKey\":\"k1\"}");

        assertThat(wrong.status()).isEqualTo(403);
        assertThat(wrong.body().get("error").asString()).isEqualTo("OUTLET_MISMATCH");
        assertThat(missing.status()).isEqualTo(403);
        assertThat(state(caseId)).isEqualTo("AWAITING_ACK");
        assertThat(countFacts(caseId, "LATE_ACK_IGNORED")).isZero();
    }

    @Test
    void invalid_body_is_400_validation_failed() {
        UUID caseId = openCase("outlet-it");
        Map<String, String> headers = Map.of("X-Outlet-Id", "outlet-it", "X-Device-Id", "tab-1");

        Resp badChoice = http("POST", "/api/cases/" + caseId + "/ack", headers,
                "{\"choice\":\"MAYBE\",\"idempotencyKey\":\"k1\"}");
        Resp noKey = http("POST", "/api/cases/" + caseId + "/ack", headers, "{\"choice\":\"STOPPED\"}");
        Resp blankKey = http("POST", "/api/cases/" + caseId + "/ack", headers,
                "{\"choice\":\"STOPPED\",\"idempotencyKey\":\" \"}");
        Resp notJson = http("POST", "/api/cases/" + caseId + "/ack", headers, "nope");

        for (Resp response : new Resp[] {badChoice, noKey, blankKey, notJson}) {
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body().get("error").asString()).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(state(caseId)).isEqualTo("AWAITING_ACK");
    }

    @Test
    void ack_during_ivr_queues_call_ended() {
        UUID caseId = openCase("outlet-it");
        clock.advance(Duration.ofSeconds(75));
        deadlineWorker.tick();
        assertThat(state(caseId)).isEqualTo("ESCALATED_IVR");

        assertThat(ack(caseId, "outlet-it", "k1", "STOPPED").status()).isEqualTo(200);

        assertThat(outboxRows(caseId)).extracting(r -> r.get("action_key")).contains("callend:" + caseId);
    }

    @Test
    void open_cases_lists_only_open_cases_of_the_outlet_with_the_deadline_times() {
        UUID open = openCase("outlet-it");
        UUID closed = openCase("outlet-it");
        openCase("outlet-other");
        ack(closed, "outlet-it", "k1", "STOPPED");

        Resp response = http("GET", "/api/outlets/outlet-it/open-cases", Map.of(), null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().size()).isEqualTo(1);
        var item = response.body().get(0);
        assertThat(item.get("caseId").asString()).isEqualTo(open.toString());
        assertThat(item.get("state").asString()).isEqualTo("AWAITING_ACK");
        assertThat(item.get("caseVersion").asInt()).isEqualTo(1);
        assertThat(item.get("orderRef").asString()).isEqualTo("4417");
        assertThat(item.get("cancelledAt").asString()).isEqualTo("2026-11-21T14:00:00.000Z");
        assertThat(item.get("l1At").asString()).isEqualTo("2026-11-21T14:00:30.000Z");
        assertThat(item.get("ivrAt").asString()).isEqualTo("2026-11-21T14:01:15.000Z");
        assertThat(item.get("expiresAt").asString()).isEqualTo("2026-11-21T14:10:00.000Z");
        assertThat(item.get("call").isNull()).isTrue();
        assertThat(http("GET", "/api/outlets/nobody/open-cases", Map.of(), null).body().size()).isZero();
    }

    @Test
    void timeline_has_sorted_events_and_derived_milliseconds_from_cancel() {
        UUID caseId = openCase("outlet-it", Duration.ofMillis(200)); // opened 200 ms after the cancel
        clock.advance(Duration.ofMillis(300));
        http("POST", "/api/cases/" + caseId + "/receipts", Map.of(),
                "{\"kind\":\"DELIVERED\",\"deviceId\":\"tab-1\",\"messageId\":\"m\"}");
        clock.advance(Duration.ofMillis(100));
        http("POST", "/api/cases/" + caseId + "/receipts", Map.of(),
                "{\"kind\":\"RENDERED\",\"deviceId\":\"tab-1\",\"messageId\":\"m\"}");
        clock.advance(Duration.ofMillis(400));
        ack(caseId, "outlet-it", "k1", "STOPPED");

        Resp response = http("GET", "/api/cases/" + caseId + "/timeline", Map.of(), null);

        assertThat(response.status()).isEqualTo(200);
        var body = response.body();
        assertThat(body.get("case").get("state").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(body.get("case").get("ackChannel").asString()).isEqualTo("TABLET");
        assertThat(body.get("case").get("closeReason").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(body.get("derived").get("firstDeliveredMs").asLong()).isEqualTo(500);
        assertThat(body.get("derived").get("firstRenderedMs").asLong()).isEqualTo(600);
        assertThat(body.get("derived").get("ackMs").asLong()).isEqualTo(1000);
        assertThat(body.get("derived").get("caseOpenMs").asLong()).isEqualTo(200);
        java.util.List<String> kinds = new java.util.ArrayList<>();
        body.get("events").forEach(e -> kinds.add(e.get("kind").asString()));
        assertThat(kinds).containsSubsequence("CASE_OPENED", "CARD_DELIVERED", "CARD_RENDERED",
                "TABLET_ACKNOWLEDGED", "CASE_CLOSED");
        assertThat(body.get("events").get(0).get("metadata").get("eligible").asString()).isEqualTo("true");
        assertThat(http("GET", "/api/cases/" + UUID.randomUUID() + "/timeline", Map.of(), null).status()).isEqualTo(404);
    }
}
