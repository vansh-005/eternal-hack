package com.nocook.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.domain.model.CancelInitiator;
import com.nocook.domain.model.CancellationEvent;
import com.nocook.domain.model.DeadlineKind;
import com.nocook.domain.model.OrderSnapshot;
import com.nocook.domain.model.OrderStatus;
import com.nocook.domain.oms.OmsView;
import com.nocook.service.application.CaseOpeningService.OpenOutcome;
import com.nocook.service.application.CaseOpeningService.OpenResult;
import com.nocook.service.ingestion.CancellationIngestor;
import com.nocook.service.oms.OmsReadPort;
import com.nocook.service.oms.OmsUnavailableException;
import com.nocook.service.persistence.DeadlineRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.awaitility.Awaitility.await;

@SpringBootTest(
        properties = {
            "nocook.scheduling.enabled=false",
            "management.otlp.metrics.export.enabled=false",
            "management.opentelemetry.tracing.export.otlp.enabled=false",
            "management.opentelemetry.logging.export.otlp.enabled=false"
        })
class CaseOpeningIT {

    static final Instant NOW = Instant.parse("2026-11-21T14:45:00.000Z");

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
            .withDatabaseName("nocook")
            .withUsername("postgres")
            .withPassword("postgres")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("init.sql"), "/docker-entrypoint-initdb.d/01-init.sql");

    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("nocook")
            .withAdminPassword("nocook");

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("NOCOOK_DB_URL", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/nocook");
        registry.add("RABBIT_HOST", RABBIT::getHost);
        registry.add("RABBIT_PORT", () -> RABBIT.getAmqpPort());
    }

    /** Test double for the OMS read seam. */
    static class FakeOms implements OmsReadPort {
        final Map<String, OmsView> views = new ConcurrentHashMap<>();

        @Override
        public OmsView get(String orderId) {
            return views.getOrDefault(orderId, new OmsView.Unavailable("no fake view"));
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeOms fakeOms() {
            return new FakeOms();
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    CancellationIngestor ingestor;

    @Autowired
    FakeOms fakeOms;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    DeadlineRepository deadlineRepository;

    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void clean() {
        jdbc.sql("truncate nocook.case_events, nocook.deadlines, nocook.outbox, nocook.call_attempts,"
                        + " nocook.webhook_events, nocook.ops_events, nocook.cases")
                .update();
    }

    private static CancellationEvent event(String orderId, int version, Instant cancelledAt) {
        return new CancellationEvent(
                UUID.randomUUID().toString(), orderId, "outlet-it", version, cancelledAt, CancelInitiator.CUSTOMER, null);
    }

    private OrderSnapshot cancelledSnapshot(String orderId, int version, Instant cancelledAt, Instant acceptedAt) {
        OrderSnapshot snapshot = new OrderSnapshot(orderId, "outlet-it", version, OrderStatus.CANCELLED, "PREP",
                acceptedAt, null, null, cancelledAt, CancelInitiator.CUSTOMER, 45000, "4417");
        fakeOms.views.put(orderId, new OmsView.Available(snapshot));
        return snapshot;
    }

    @Test
    void eligibleCancellationOpensCaseWithThreeDeadlinesAndOneCardIntent() {
        Instant cancelledAt = NOW.minusSeconds(2);
        cancelledSnapshot("ord-1", 3, cancelledAt, NOW.minusSeconds(120));

        OpenResult result = ingestor.ingest(event("ord-1", 3, cancelledAt), NOW.minusMillis(40));

        assertThat(result.outcome()).isEqualTo(OpenOutcome.OPENED);
        assertThat(jdbc.sql("select state from nocook.cases where id = :id").param("id", result.caseId())
                        .query(String.class).single()).isEqualTo("AWAITING_ACK");
        List<String> kinds = jdbc.sql("select kind || '@' || extract(epoch from due_at)::bigint from nocook.deadlines"
                        + " where case_id = :id order by due_at")
                .param("id", result.caseId()).query(String.class).list();
        assertThat(kinds).containsExactly(
                "L1@" + cancelledAt.plusSeconds(30).getEpochSecond(),
                "IVR@" + cancelledAt.plusSeconds(75).getEpochSecond(),
                "EXPIRY@" + cancelledAt.plusSeconds(600).getEpochSecond());
        List<Map<String, Object>> outbox = jdbc.sql("select type, action_key, status, payload ->> 'kind' as kind,"
                        + " payload ->> 'outletId' as outlet, (payload ->> 'caseVersion')::int as v from nocook.outbox")
                .query().listOfRows();
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0)).containsEntry("type", "MQTT_CARD")
                .containsEntry("action_key", "card:" + result.caseId() + ":OPEN:1")
                .containsEntry("status", "PENDING")
                .containsEntry("kind", "OPEN")
                .containsEntry("outlet", "outlet-it")
                .containsEntry("v", 1);
        assertThat(jdbc.sql("select kind from nocook.case_events where case_id = :id").param("id", result.caseId())
                        .query(String.class).list()).containsExactly("CASE_OPENED");
        assertThat(meterRegistry.counter("nocook.events.consumed", "outcome", "opened").count()).isGreaterThan(0);
        assertThat(meterRegistry.counter("nocook.cases.opened").count()).isGreaterThan(0);
    }

    @Test
    void notActionableCancellationStoresReasonAndNothingElse() {
        Instant cancelledAt = NOW.minusSeconds(2);
        // accepted AFTER the cancel: NOT_ACCEPTED_BEFORE_CANCEL
        cancelledSnapshot("ord-2", 2, cancelledAt, NOW);

        OpenResult result = ingestor.ingest(event("ord-2", 2, cancelledAt), null);

        assertThat(result.outcome()).isEqualTo(OpenOutcome.NOT_ACTIONABLE);
        Map<String, Object> row = jdbc.sql("select state, not_actionable_reason, closed_at from nocook.cases")
                .query().singleRow();
        assertThat(row.get("state")).isEqualTo("NOT_ACTIONABLE");
        assertThat(row.get("not_actionable_reason")).isEqualTo("NOT_ACCEPTED_BEFORE_CANCEL");
        assertThat(row.get("closed_at")).isNotNull();
        assertThat(count("nocook.deadlines")).isZero();
        assertThat(count("nocook.outbox")).isZero();
        assertThat(jdbc.sql("select kind from nocook.case_events").query(String.class).list())
                .containsExactly("NOT_ACTIONABLE");
    }

    @Test
    void duplicateEventIsRecordedAsFactAndCreatesNothingNew() {
        Instant cancelledAt = NOW.minusSeconds(2);
        cancelledSnapshot("ord-3", 1, cancelledAt, NOW.minusSeconds(60));
        CancellationEvent event = event("ord-3", 1, cancelledAt);

        OpenResult first = ingestor.ingest(event, null);
        OpenResult second = ingestor.ingest(event, null);

        assertThat(first.outcome()).isEqualTo(OpenOutcome.OPENED);
        assertThat(second.outcome()).isEqualTo(OpenOutcome.DUPLICATE);
        assertThat(second.caseId()).isEqualTo(first.caseId());
        assertThat(count("nocook.cases")).isEqualTo(1);
        assertThat(count("nocook.deadlines")).isEqualTo(3);
        assertThat(count("nocook.outbox")).isEqualTo(1);
        assertThat(jdbc.sql("select kind from nocook.case_events order by server_at, kind").query(String.class).list())
                .containsExactlyInAnyOrder("CASE_OPENED", "DUPLICATE_EVENT");
    }

    @Test
    void unavailableOmsThrowsSoTheContainerRequeues() {
        fakeOms.views.put("ord-4", new OmsView.Unavailable("timeout"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ingestor.ingest(event("ord-4", 1, NOW.minusSeconds(1)), null))
                .isInstanceOf(OmsUnavailableException.class);
        assertThat(count("nocook.cases")).isZero();
    }

    @Test
    void deadlineRepositoryClaimsDueRowsAndReapsExpiredLeases() {
        Instant cancelledAt = NOW.minusSeconds(40);
        cancelledSnapshot("ord-5", 1, cancelledAt, NOW.minusSeconds(100));
        OpenResult result = ingestor.ingest(event("ord-5", 1, cancelledAt), null);

        List<DeadlineRepository.ClaimedDeadline> claimed =
                deadlineRepository.claimDue(NOW, 50, "w1", NOW.plusSeconds(10));
        assertThat(claimed).extracting(DeadlineRepository.ClaimedDeadline::kind).containsExactly(DeadlineKind.L1);
        assertThat(claimed.get(0).caseId()).isEqualTo(result.caseId());
        assertThat(claimed.get(0).attempts()).isEqualTo(1);

        assertThat(deadlineRepository.reapExpiredLeases(NOW)).isEmpty();
        List<DeadlineRepository.ReapedLease> reaped = deadlineRepository.reapExpiredLeases(NOW.plusSeconds(11));
        assertThat(reaped).hasSize(1);
        assertThat(reaped.get(0).previousOwner()).isEqualTo("w1");
    }

    @Test
    void realAmqpMessageOpensCaseAndInvalidMessageGoesToDlq() {
        Instant cancelledAt = NOW.minusSeconds(1);
        cancelledSnapshot("ord-6", 1, cancelledAt, NOW.minusSeconds(60));
        String eventId = UUID.randomUUID().toString();
        String valid = "{\"schemaVersion\":1,\"eventId\":\"" + eventId + "\",\"type\":\"order.cancelled\","
                + "\"orderId\":\"ord-6\",\"outletId\":\"outlet-it\",\"orderVersion\":1,"
                + "\"occurredAt\":\"" + cancelledAt + "\",\"initiatedBy\":\"CUSTOMER\",\"runId\":null,\"synthetic\":false}";
        rabbitTemplate.send("order.events", "order.cancelled", json(valid, NOW.minusMillis(5).toString()));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(count("nocook.cases")).isEqualTo(1));

        String invalid = "{\"schemaVersion\":2,\"eventId\":\"x\"}";
        rabbitTemplate.send("order.events", "order.cancelled", json(invalid, NOW.toString()));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(rabbitTemplate.receive("nocook.cancellations.dlq", 500)).isNotNull());
        assertThat(count("nocook.cases")).isEqualTo(1);
    }

    private static Message json(String body, String publishedAt) {
        return MessageBuilder.withBody(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setHeader("x-published-at", publishedAt)
                .build();
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }
}
