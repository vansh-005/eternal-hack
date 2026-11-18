package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nocook.domain.model.OrderSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/** R1: the same cancellation published 20x concurrently to the real queue yields exactly one case. */
class DuplicateStormIT extends AbstractIT {

    private static final int COPIES = 20;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Test
    void twenty_concurrent_copies_open_exactly_one_case_and_nineteen_duplicate_facts() throws Exception {
        OrderSnapshot snapshot = cancelledOrder("ord-r1", Duration.ofMillis(500));
        String body = amqpBody(snapshot, UUID.randomUUID().toString());
        double duplicatesBefore = counter("nocook.events.consumed", "outcome", "duplicate");

        ExecutorService pool = Executors.newFixedThreadPool(COPIES);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> sends = new ArrayList<>();
        for (int i = 0; i < COPIES; i++) {
            sends.add(pool.submit(() -> {
                startGun.await();
                rabbitTemplate.send("order.events", "order.cancelled", message(body));
                return null;
            }));
        }
        startGun.countDown();
        for (Future<?> send : sends) {
            send.get();
        }
        pool.shutdown();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(factCount("DUPLICATE_EVENT")).isEqualTo(COPIES - 1));

        long cases = count("nocook.cases");
        long duplicateFacts = factCount("DUPLICATE_EVENT");
        double duplicateCounter = counter("nocook.events.consumed", "outcome", "duplicate") - duplicatesBefore;
        ItSummary.print("R1", Map.of("copies", COPIES, "cases", cases, "duplicateFacts", duplicateFacts,
                "duplicateCounter", (long) duplicateCounter), cases == 1 && duplicateFacts == COPIES - 1);

        assertThat(cases).isEqualTo(1);
        assertThat(count("nocook.deadlines")).isEqualTo(3);
        assertThat(count("nocook.outbox")).isEqualTo(1);
        assertThat(duplicateCounter).isEqualTo(COPIES - 1);
    }

    private static Message message(String body) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
    }
}
