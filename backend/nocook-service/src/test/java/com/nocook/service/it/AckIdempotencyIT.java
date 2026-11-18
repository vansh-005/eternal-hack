package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** R13: same idempotencyKey twice is 200 duplicate=true; a different key after the ACK is 409 ALREADY_ACKNOWLEDGED. */
class AckIdempotencyIT extends AbstractIT {

    @Test
    void replayed_key_is_a_duplicate_and_a_new_key_after_ack_conflicts() {
        UUID caseId = openEligibleCase("ord-r13");
        RestClient http = PendingSupport.http(port);

        var first = PendingSupport.ack(http, caseId, "outlet-it", "STOPPED", "key-1");
        var replay = PendingSupport.ack(http, caseId, "outlet-it", "STOPPED", "key-1");
        var other = PendingSupport.ack(http, caseId, "outlet-it", "STOPPED", "key-2");
        var wrongOutlet = PendingSupport.ack(http, caseId, "outlet-other", "STOPPED", "key-3");

        ItSummary.print("R13", Map.of("first", first.status(), "replay", replay.status(), "other", other.status()),
                first.status() == 200 && replay.status() == 200 && other.status() == 409);

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("state").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(first.body().get("duplicate").asBoolean()).isFalse();
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("duplicate").asBoolean()).isTrue();
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.body().get("error").asString()).isEqualTo("ALREADY_ACKNOWLEDGED");
        assertThat(wrongOutlet.status()).isEqualTo(403);
        assertThat(factKinds(caseId).stream().filter("TABLET_ACKNOWLEDGED"::equals).count()).isEqualTo(1);
    }
}
