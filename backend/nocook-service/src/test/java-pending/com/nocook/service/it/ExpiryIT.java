package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.service.scheduling.DeadlineWorker;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;

/** R8: no ACK; at +10 min the case EXPIRES; later ticks are no-ops; a late ACK gets 409 CASE_CLOSED. */
class ExpiryIT extends AbstractIT {

    @Autowired DeadlineWorker worker;

    @Test
    void case_expires_at_ten_minutes_and_late_ack_is_rejected_and_recorded() {
        UUID caseId = openEligibleCase("ord-r8");
        // cancelledAt = T0 - 500 ms, so EXPIRY is due at T0 + 599.5 s
        clock.advance(Duration.ofMinutes(10));
        worker.tick();
        worker.tick();

        long factsAfterExpiry = factKinds(caseId).size();
        clock.advance(Duration.ofMinutes(1));
        worker.tick();
        assertThat(factKinds(caseId)).as("later ticks are no-ops").hasSize((int) factsAfterExpiry);
        assertThat(stateOf(caseId)).isEqualTo("EXPIRED");
        assertThat(count("nocook.call_attempts")).isZero();

        RestClient http = PendingSupport.http(port);
        PendingSupport.Reply late = PendingSupport.ack(http, caseId, "outlet-it", "STOPPED", "late-1");

        ItSummary.print("R8", Map.of("state", stateOf(caseId), "lateAckStatus", late.status(),
                "error", late.body().path("error").asString("")),
                late.status() == 409 && stateOf(caseId).equals("EXPIRED"));
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.body().get("error").asString()).isEqualTo("CASE_CLOSED");
        assertThat(factKinds(caseId)).contains("CASE_EXPIRED", "LATE_ACK_IGNORED");
        assertThat(stateOf(caseId)).isEqualTo("EXPIRED");
    }
}
