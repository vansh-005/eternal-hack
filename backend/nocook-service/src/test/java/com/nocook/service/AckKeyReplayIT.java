package com.nocook.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** R13 (B-05 side): idempotency of the ACK endpoint. */
class AckKeyReplayIT extends B0xBaseIT {

    @Test
    void same_key_twice_is_200_then_200_duplicate_and_different_key_is_409() {
        UUID caseId = openCase("outlet-it");

        Resp first = ack(caseId, "outlet-it", "key-1", "STOPPED");
        Resp replay = ack(caseId, "outlet-it", "key-1", "STOPPED");
        Resp other = ack(caseId, "outlet-it", "key-2", "STOPPED");

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("duplicate").asBoolean()).isFalse();
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("duplicate").asBoolean()).isTrue();
        assertThat(replay.body().get("choice").asString()).isEqualTo("STOPPED");
        assertThat(replay.body().get("ackedAt").asString()).isEqualTo(first.body().get("ackedAt").asString());
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.body().get("error").asString()).isEqualTo("ALREADY_ACKNOWLEDGED");
        assertThat(countFacts(caseId, "TABLET_ACKNOWLEDGED")).isEqualTo(1);
        assertThat(outboxRows(caseId).stream().filter(r -> "CLOSE".equals(r.get("kind"))).count()).isEqualTo(1);
    }

    @Test
    void twenty_concurrent_acks_with_one_key_apply_exactly_once() {
        UUID caseId = openCase("outlet-it");

        List<CompletableFuture<Resp>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> ack(caseId, "outlet-it", "same-key", "STOPPED")));
        }
        List<Resp> responses = calls.stream().map(CompletableFuture::join).toList();

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
        assertThat(responses.stream().filter(r -> !r.body().get("duplicate").asBoolean()).count()).isEqualTo(1);
        assertThat(countFacts(caseId, "TABLET_ACKNOWLEDGED")).isEqualTo(1);
        assertThat(state(caseId)).isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void twenty_concurrent_acks_with_distinct_keys_have_exactly_one_winner() {
        UUID caseId = openCase("outlet-it");

        List<CompletableFuture<Resp>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String key = "key-" + i;
            calls.add(CompletableFuture.supplyAsync(() -> ack(caseId, "outlet-it", key, "STOPPED")));
        }
        List<Resp> responses = calls.stream().map(CompletableFuture::join).toList();

        assertThat(responses.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
        assertThat(responses.stream().filter(r -> r.status() == 409).count()).isEqualTo(19);
        assertThat(countFacts(caseId, "TABLET_ACKNOWLEDGED")).isEqualTo(1);
        assertThat(countFacts(caseId, "LATE_ACK_IGNORED")).isEqualTo(19);
    }
}
