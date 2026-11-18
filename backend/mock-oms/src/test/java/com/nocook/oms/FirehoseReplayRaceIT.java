package com.nocook.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.otlp.metrics.export.enabled=false",
            "management.opentelemetry.tracing.export.otlp.enabled=false",
            "management.opentelemetry.logging.export.otlp.enabled=false"
        })
class FirehoseReplayRaceIT {

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
        registry.add("OMS_DB_URL", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/nocook");
        registry.add("RABBIT_HOST", RABBIT::getHost);
        registry.add("RABBIT_PORT", () -> RABBIT.getAmqpPort());
    }

    @LocalServerPort
    int port;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    RabbitAdmin rabbitAdmin;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port + "/oms/api").build();
    }

    private JsonNode post(String path, String body) {
        return client().post().uri(path).header("content-type", "application/json").body(body)
                .retrieve().body(JsonNode.class);
    }

    @Test
    void firehoseHoldsModerateRateAndTagsMessagesSynthetic() throws Exception {
        Queue spy = new AnonymousQueue();
        rabbitAdmin.declareQueue(spy);
        rabbitAdmin.declareBinding(BindingBuilder.bind(spy).to(new TopicExchange("order.events")).with("order.placed"));
        rabbitAdmin.purgeQueue("nocook.cancellations", true);

        post("/firehose", "{\"eventsPerSecond\":2000}");
        Thread.sleep(7000);
        JsonNode status = client().get().uri("/firehose").retrieve().body(JsonNode.class);
        JsonNode stopped = client().delete().uri("/firehose").retrieve().body(JsonNode.class);

        assertThat(status.get("running").asBoolean()).isTrue();
        double achieved = status.get("achievedPerSecond").asDouble();
        assertThat(achieved).isBetween(1700.0, 2300.0);
        assertThat(stopped.get("running").asBoolean()).isFalse();
        assertThat(stopped.get("published").asLong()).isGreaterThan(10_000);
        // nothing reaches the cancellation queue
        assertThat(rabbitAdmin.getQueueInfo("nocook.cancellations").getMessageCount()).isZero();

        Message sample = rabbitTemplate.receive(spy.getName(), 3000);
        assertThat(sample).isNotNull();
        assertThat(sample.getMessageProperties().getHeaders()).containsEntry("x-synthetic", "true");
        JsonNode payload = jsonMapper.readTree(sample.getBody());
        assertThat(payload.get("synthetic").asBoolean()).isTrue();
        assertThat(payload.get("outletId").asString()).startsWith("outlet-load-");
        assertThat(sample.getBody().length).isBetween(280, 360);
        assertThat(client().get().uri("/firehose").retrieve().body(JsonNode.class).get("running").asBoolean())
                .isFalse();
    }

    @Test
    void replayRepublishesSameEventIdsWithReplayHeader() {
        List<String> orderIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = post("/load/accepted-orders",
                            "{\"outletId\":\"outlet-replay\",\"acceptedSecondsAgo\":60,\"valuePaise\":45000}")
                    .get("orderId").asString();
            post("/orders/" + id + "/cancel", "{\"initiatedBy\":\"CUSTOMER\"}");
            orderIds.add(id);
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                        jdbc.sql("select count(*) from oms.outbox where status = 'PENDING'").query(Long.class).single())
                .isZero());
        rabbitAdmin.purgeQueue("nocook.cancellations", true);

        JsonNode response = post("/test/replay-cancellations", "{\"lastN\":2,\"times\":3}");
        assertThat(response.get("republished").asLong()).isEqualTo(6);

        List<Message> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Message next = rabbitTemplate.receive("nocook.cancellations");
            if (next != null) {
                received.add(next);
            }
            assertThat(received).hasSize(6);
        });
        Set<String> eventIds = new HashSet<>();
        Set<String> orders = new HashSet<>();
        for (Message message : received) {
            assertThat(message.getMessageProperties().getHeaders()).containsEntry("x-replay", "true");
            JsonNode payload = jsonMapper.readTree(message.getBody());
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(payload.get("eventId").asString());
            assertThat(payload.get("type").asString()).isEqualTo("order.cancelled");
            assertThat(payload.has("traceparent")).isFalse();
            eventIds.add(payload.get("eventId").asString());
            orders.add(payload.get("orderId").asString());
        }
        assertThat(eventIds).hasSize(2);
        assertThat(orders).containsExactlyInAnyOrder(orderIds.get(1), orderIds.get(2));
    }

    @Test
    void raceMarksReadyBeforeCancelForEveryOrder() {
        JsonNode response = post("/test/race/ready-then-cancel",
                "{\"count\":25,\"outletId\":\"outlet-race-it\",\"gapMillis\":5}");
        List<String> orderIds = new ArrayList<>();
        response.get("orderIds").forEach(node -> orderIds.add(node.asString()));
        assertThat(orderIds).hasSize(25).doesNotHaveDuplicates();

        for (String orderId : orderIds) {
            JsonNode snapshot = client().get().uri("/orders/" + orderId).retrieve().body(JsonNode.class);
            assertThat(snapshot.get("status").asString()).isEqualTo("CANCELLED");
            assertThat(snapshot.get("readyAt").isNull()).isFalse();
            assertThat(snapshot.get("cancelInitiator").asString()).isEqualTo("CUSTOMER");
            assertThat(snapshot.get("readyAt").asString()).isLessThanOrEqualTo(snapshot.get("cancelledAt").asString());
        }
        List<String> eventOrder = jdbc.sql("""
                        select routing_key from oms.outbox
                        where payload->>'orderId' = :id order by created_at, id""")
                .param("id", orderIds.get(0))
                .query(String.class)
                .list();
        assertThat(eventOrder).containsExactly("order.accepted", "order.ready", "order.cancelled");
    }
}
