package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.service.scheduling.DeadlineWorker;
import com.nocook.service.scheduling.OutboxDispatcher;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;

/** R10: bad/missing signature 401; duplicate providerEventId has no effect; DTMF 1 acknowledges via IVR. */
class WebhookSecurityIT extends AbstractIT {

    @Autowired DeadlineWorker worker;
    @Autowired OutboxDispatcher dispatcher;

    @Test
    void signatures_are_enforced_events_are_deduped_and_dtmf_one_acknowledges() {
        UUID caseId = openEligibleCase("ord-r10");
        clock.advance(Duration.ofSeconds(76));
        worker.tick(); // L1 then IVR reservation
        worker.tick();
        dispatcher.tick(); // PLACE_CALL -> gateway
        String providerCallId = jdbc.sql("select provider_call_id from nocook.call_attempts where case_id = :id")
                .param("id", caseId).query(String.class).single();
        RestClient http = PendingSupport.http(port);

        String ringing = "{\"providerCallId\":\"" + providerCallId + "\",\"providerEventId\":\"e-1\","
                + "\"status\":\"RINGING\",\"at\":\"" + clock.instant() + "\"}";
        var badSignature = PendingSupport.webhook(http, "status", ringing, "deadbeef");
        var missingSignature = PendingSupport.webhook(http, "status", ringing, null);
        var first = PendingSupport.webhook(http, "status", ringing, PendingSupport.sign(ringing));
        var second = PendingSupport.webhook(http, "status", ringing, PendingSupport.sign(ringing));

        String dtmf = "{\"providerCallId\":\"" + providerCallId + "\",\"providerEventId\":\"e-2\","
                + "\"digits\":\"1\",\"at\":\"" + clock.instant() + "\"}";
        var dtmfReply = PendingSupport.webhook(http, "dtmf", dtmf, PendingSupport.sign(dtmf));

        String channel = jdbc.sql("select ack_channel from nocook.cases where id = :id").param("id", caseId)
                .query(String.class).single();
        long ringingWebhooks = jdbc.sql("select count(*) from nocook.webhook_events where provider_event_id = 'e-1'")
                .query(Long.class).single();
        ItSummary.print("R10", Map.of("bad", badSignature.status(), "missing", missingSignature.status(),
                "duplicate", second.body().path("duplicate").asBoolean(false), "ackChannel", channel),
                badSignature.status() == 401 && missingSignature.status() == 401 && channel.equals("IVR"));

        assertThat(badSignature.status()).isEqualTo(401);
        assertThat(missingSignature.status()).isEqualTo(401);
        assertThat(first.status()).isEqualTo(200);
        assertThat(second.body().get("duplicate").asBoolean()).isTrue();
        assertThat(ringingWebhooks).isEqualTo(1);
        assertThat(dtmfReply.status()).isEqualTo(200);
        assertThat(stateOf(caseId)).isEqualTo("ACKNOWLEDGED");
        assertThat(channel).isEqualTo("IVR");
        assertThat(factKinds(caseId)).contains("DTMF_ACKNOWLEDGED");
    }
}
