package com.nocook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nocook.domain.effect.Effect;
import com.nocook.domain.model.CaseState;
import com.nocook.service.application.CaseContext;
import com.nocook.service.application.EffectApplier;
import com.nocook.service.telephony.WebhookSigner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** B-06: reservation, dispatch, mock provider, signed webhooks, DTMF to ACK, auto-outcomes. */
class TelephonyFlowIT extends B0xBaseIT {

    @Autowired
    WebhookSigner signer;

    @Autowired
    EffectApplier effectApplier;

    @Autowired
    TransactionTemplate transaction;

    /** Opens a case, runs it to ESCALATED_IVR and dispatches everything (PLACE_CALL then INCOMING_CALL). */
    private UUID caseDialed(String outletId) {
        UUID caseId = openCase(outletId);
        clock.advance(Duration.ofSeconds(75));
        deadlineWorker.tick();
        assertThat(state(caseId)).isEqualTo("ESCALATED_IVR");
        dispatcher.tick();
        return caseId;
    }

    private String callStatus(UUID caseId) {
        return jdbc.sql("select status from nocook.call_attempts where case_id = :id").param("id", caseId)
                .query(String.class).single();
    }

    private String providerCallId(UUID caseId) {
        return jdbc.sql("select provider_call_id from nocook.call_attempts where case_id = :id").param("id", caseId)
                .query(String.class).single();
    }

    private Resp signedPost(String path, String body, String signature) {
        return http("POST", path, Map.of("X-Mock-Signature", signature), body);
    }

    private Resp signedStatus(String callId, String eventId, String status) {
        String body = "{\"providerCallId\":\"" + callId + "\",\"providerEventId\":\"" + eventId
                + "\",\"status\":\"" + status + "\",\"at\":\"2026-11-21T14:01:20.000Z\"}";
        return signedPost("/api/webhooks/telephony/status", body, signer.sign(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void full_flow_dial_answer_dtmf_acknowledges_through_the_ivr_channel_and_ends_the_call() {
        UUID caseId = caseDialed("outlet-it");

        assertThat(callStatus(caseId)).isEqualTo("DIALING");
        String callId = providerCallId(caseId);
        assertThat(callId).startsWith("mock-");
        assertThat(gateway.placeCalls.get()).isEqualTo(1);
        assertThat(mqtt.calls()).singleElement().satisfies(call -> {
            assertThat(call.json().get("kind").asString()).isEqualTo("INCOMING_CALL");
            assertThat(call.json().get("providerCallId").asString()).isEqualTo(callId);
            assertThat(call.json().get("script").asString()).contains("ending 4417").contains("Press 1");
            assertThat(call.json().get("sentAt").asString()).isNotBlank();
        });
        var open = http("GET", "/api/outlets/outlet-it/open-cases", Map.of(), null).body().get(0);
        assertThat(open.get("call").get("providerCallId").asString()).isEqualTo(callId);
        assertThat(open.get("call").get("status").asString()).isEqualTo("DIALING");

        assertThat(http("POST", "/api/mock-telephony/calls/" + callId + "/answer", Map.of(), null).status()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(callStatus(caseId)).isEqualTo("ANSWERED"));

        assertThat(http("POST", "/api/mock-telephony/calls/" + callId + "/dtmf", Map.of(), "{\"digit\":\"1\"}").status())
                .isEqualTo(202);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(state(caseId)).isEqualTo("ACKNOWLEDGED"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(callStatus(caseId)).isEqualTo("COMPLETED"));

        assertThat(jdbc.sql("select ack_channel || '/' || ack_choice || '/' || ack_actor from nocook.cases where id = :id")
                        .param("id", caseId).query(String.class).single()).isEqualTo("IVR/STOPPED/" + callId);
        assertThat(factKinds(caseId)).containsSubsequence("CASE_OPENED", "L1_TRIGGERED", "IVR_ESCALATED", "IVR_RESERVED",
                "CALL_DIALING", "CALL_ANSWERED", "DTMF_ACKNOWLEDGED", "CASE_CLOSED");
        assertThat(jdbc.sql("select outcome_digit from nocook.call_attempts where case_id = :id").param("id", caseId)
                        .query(String.class).single()).isEqualTo("1");

        dispatcher.tick();
        assertThat(mqtt.calls()).extracting(c -> c.json().get("kind").asString())
                .containsExactly("INCOMING_CALL", "CALL_ENDED");
        assertThat(counter("nocook.acks", "channel", "IVR", "choice", "STOPPED")).isGreaterThan(0);
        assertThat(counter("nocook.webhooks", "kind", "dtmf", "result", "ok")).isGreaterThan(0);
    }

    @Test
    void case_closed_before_dispatch_cancels_the_call_without_dialing() {
        UUID caseId = openCase("outlet-it");
        clock.advance(Duration.ofSeconds(75));
        deadlineWorker.tick();
        assertThat(ack(caseId, "outlet-it", "k1", "STOPPED").status()).isEqualTo(200);

        dispatcher.tick();

        assertThat(callStatus(caseId)).isEqualTo("CANCELLED");
        assertThat(gateway.placeCalls.get()).isZero();
        assertThat(countFacts(caseId, "CALL_CANCELLED")).isEqualTo(1);
        assertThat(mqtt.calls()).extracting(c -> c.json().get("kind").asString()).containsExactly("CALL_ENDED");
        assertThat(outboxRows(caseId)).allSatisfy(r -> assertThat(r.get("status")).isEqualTo("SENT"));
    }

    @Test
    void gateway_failure_leaves_unknown_and_is_never_retried() {
        UUID caseId = openCase("outlet-it");
        clock.advance(Duration.ofSeconds(75));
        deadlineWorker.tick();
        gateway.failPlaceCall = true;

        dispatcher.tick();
        gateway.failPlaceCall = false;
        clock.advance(Duration.ofMinutes(1));
        dispatcher.tick();

        assertThat(callStatus(caseId)).isEqualTo("UNKNOWN");
        assertThat(gateway.placeCalls.get()).isEqualTo(1);
        assertThat(countFacts(caseId, "CALL_UNKNOWN")).isEqualTo(1);
        assertThat(jdbc.sql("select status from nocook.outbox where action_key = :k").param("k", "ivr:" + caseId)
                        .query(String.class).single()).isEqualTo("SENT");
        assertThat(mqtt.calls()).isEmpty();
    }

    @Test
    void reserve_ivr_twice_creates_one_attempt_one_intent_and_a_duplicate_fact() {
        UUID caseId = openCase("outlet-it");
        CaseContext context = new CaseContext(caseId, orderIdOf(caseId), 2, "4417", "outlet-it",
                CaseState.ESCALATED_IVR, 3, clock.instant(), clock.instant(), null, null, null);

        for (int i = 0; i < 2; i++) {
            transaction.executeWithoutResult(
                    status -> effectApplier.apply(context, List.of(new Effect.ReserveIvr()), clock.instant()));
        }

        assertThat(jdbc.sql("select count(*) from nocook.call_attempts where case_id = :id").param("id", caseId)
                        .query(Long.class).single()).isEqualTo(1L);
        assertThat(outboxRows(caseId).stream().filter(r -> "PLACE_CALL".equals(r.get("type"))).count()).isEqualTo(1);
        assertThat(countFacts(caseId, "IVR_RESERVED")).isEqualTo(1);
        assertThat(countFacts(caseId, "IVR_DUPLICATE_PREVENTED")).isEqualTo(1);
        assertThat(counter("nocook.ivr.duplicate_prevented")).isGreaterThan(0);
    }

    @Test
    void webhooks_reject_bad_signatures_ignore_replays_and_never_regress_a_terminal_call() {
        UUID caseId = caseDialed("outlet-it");
        String callId = providerCallId(caseId);
        String body = "{\"providerCallId\":\"" + callId + "\",\"providerEventId\":\"evt-x\",\"status\":\"ANSWERED\"}";

        Resp forged = signedPost("/api/webhooks/telephony/status", body, "00".repeat(32));
        Resp unsigned = http("POST", "/api/webhooks/telephony/status", Map.of(), body);
        assertThat(forged.status()).isEqualTo(401);
        assertThat(forged.body().get("error").asString()).isEqualTo("BAD_SIGNATURE");
        assertThat(unsigned.status()).isEqualTo(401);
        assertThat(callStatus(caseId)).isEqualTo("DIALING");

        Resp first = signedStatus(callId, "evt-1", "RINGING");
        Resp replay = signedStatus(callId, "evt-1", "RINGING");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("duplicate").asBoolean()).isFalse();
        assertThat(replay.body().get("duplicate").asBoolean()).isTrue();
        assertThat(countFacts(caseId, "CALL_RINGING")).isEqualTo(1);

        signedStatus(callId, "evt-2", "NO_ANSWER");
        signedStatus(callId, "evt-3", "RINGING");
        signedStatus(callId, "evt-4", "ANSWERED");
        assertThat(callStatus(caseId)).isEqualTo("NO_ANSWER");
        assertThat(countFacts(caseId, "CALL_ANSWERED")).isZero();
        assertThat(signedStatus(callId, "evt-5", "WAT").status()).isEqualTo(400);
        assertThat(signedStatus("mock-unknown", "evt-6", "RINGING").body().get("ignored").asBoolean()).isTrue();
        assertThat(counter("nocook.webhooks", "kind", "status", "result", "bad_signature")).isGreaterThan(1);
    }

    @Test
    void dtmf_with_an_unknown_digit_is_only_an_event_and_replays_do_not_acknowledge_twice() {
        UUID caseId = caseDialed("outlet-it");
        String callId = providerCallId(caseId);

        Resp star = dtmf(callId, "evt-a", "9");
        assertThat(star.status()).isEqualTo(200);
        assertThat(state(caseId)).isEqualTo("ESCALATED_IVR");
        assertThat(countFacts(caseId, "DTMF_IGNORED")).isEqualTo(1);

        assertThat(dtmf(callId, "evt-b", "2").status()).isEqualTo(200);
        assertThat(dtmf(callId, "evt-b", "2").body().get("duplicate").asBoolean()).isTrue();
        assertThat(state(caseId)).isEqualTo("ACKNOWLEDGED");
        assertThat(jdbc.sql("select ack_choice from nocook.cases where id = :id").param("id", caseId)
                        .query(String.class).single()).isEqualTo("NOT_STARTED");
        assertThat(countFacts(caseId, "DTMF_ACKNOWLEDGED")).isEqualTo(1);

        // a later digit from a different event on a closed case is a late ack, not a second close
        assertThat(dtmf(callId, "evt-c", "1").status()).isEqualTo(200);
        assertThat(countFacts(caseId, "LATE_ACK_IGNORED")).isEqualTo(1);
    }

    @Test
    void answered_after_the_case_closed_hangs_up_and_records_it() {
        UUID caseId = caseDialed("outlet-it");
        String callId = providerCallId(caseId);
        assertThat(ack(caseId, "outlet-it", "k1", "STOPPED").status()).isEqualTo(200);

        signedStatus(callId, "evt-1", "ANSWERED");

        assertThat(countFacts(caseId, "CALL_CANCELLED")).isEqualTo(1);
        assertThat(callStatus(caseId)).isEqualTo("COMPLETED");
    }

    @Test
    void load_outlet_calls_play_a_provider_script_through_the_signed_webhooks() {
        UUID caseId = caseDialed("outlet-load-0001");
        String callId = providerCallId(caseId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(callStatus(caseId))
                .isIn("COMPLETED", "NO_ANSWER", "BUSY"));

        assertThat(callId).startsWith("mock-");
        assertThat(factKinds(caseId)).containsAnyOf("CALL_BUSY", "CALL_NO_ANSWER", "CALL_COMPLETED");
    }

    private Resp dtmf(String callId, String eventId, String digits) {
        String body = "{\"providerCallId\":\"" + callId + "\",\"providerEventId\":\"" + eventId
                + "\",\"digits\":\"" + digits + "\"}";
        return signedPost("/api/webhooks/telephony/dtmf", body, signer.sign(body.getBytes(StandardCharsets.UTF_8)));
    }
}
