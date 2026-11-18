package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.nocook.domain.model.CancellationEvent;
import com.nocook.domain.model.OrderSnapshot;
import com.nocook.service.ingestion.CancellationIngestor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** R12: listener fails after the case transaction committed, so the broker redelivers; nothing is lost or doubled. */
class CrashAfterCommitBeforeAckIT extends AbstractIT {

    @MockitoSpyBean
    CancellationIngestor spiedIngestor;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    RabbitAdmin rabbitAdmin;

    @Test
    void redelivery_after_post_commit_failure_yields_one_case_and_one_duplicate_fact() {
        AtomicInteger invocations = new AtomicInteger();
        doAnswer(invocation -> {
                    Object result = invocation.callRealMethod();
                    if (invocations.incrementAndGet() == 1) {
                        throw new IllegalStateException("simulated crash after commit, before ack");
                    }
                    return result;
                })
                .when(spiedIngestor)
                .ingest(any(CancellationEvent.class), any());

        OrderSnapshot snapshot = cancelledOrder("ord-r12", Duration.ofMillis(500));
        String body = amqpBody(snapshot, UUID.randomUUID().toString());
        rabbitTemplate.send("order.events", "order.cancelled", MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setHeader("x-published-at", Instant.now().toString())
                .build());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(factCount("DUPLICATE_EVENT")).isEqualTo(1);
            assertThat(rabbitAdmin.getQueueInfo("nocook.cancellations").getMessageCount()).isZero();
        });

        long cases = count("nocook.cases");
        long duplicates = factCount("DUPLICATE_EVENT");
        ItSummary.print("R12", Map.of("deliveries", invocations.get(), "cases", cases, "duplicateFacts", duplicates),
                cases == 1 && duplicates == 1 && invocations.get() == 2);

        assertThat(invocations.get()).isEqualTo(2);
        assertThat(cases).isEqualTo(1);
        assertThat(count("nocook.deadlines")).isEqualTo(3);
        assertThat(count("nocook.outbox")).isEqualTo(1);
        assertThat(rabbitAdmin.getQueueInfo("nocook.cancellations.dlq").getMessageCount()).isZero();
    }
}
