package com.nocook.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.wait.strategy.Wait;
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
class OrderLifecycleIT {

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

    private String createOrder(String outletId) {
        String body = "{\"outletId\":\"" + outletId + "\",\"items\":[{\"name\":\"Margherita\",\"qty\":2,\"pricePaise\":45000}]}";
        JsonNode created = client().post().uri("/orders").header("content-type", "application/json")
                .header("X-Run-Id", "run-it-1").body(body).retrieve().body(JsonNode.class);
        assertThat(created.get("status").asString()).isEqualTo("PLACED");
        return created.get("orderId").asString();
    }

    @Test
    void createAcceptCancelYieldsExactlyOneCancelledMessageWithPayload() {
        Queue spy = new AnonymousQueue();
        rabbitAdmin.declareQueue(spy);
        rabbitAdmin.declareBinding(BindingBuilder.bind(spy).to(new TopicExchange("order.events")).with("order.#"));
        // drain anything left on the real queue by other tests so counts are exact
        rabbitAdmin.purgeQueue("nocook.cancellations", true);

        JsonNode created = client().post().uri("/orders").header("content-type", "application/json")
                .header("X-Run-Id", "run-it-1")
                .body("{\"outletId\":\"outlet-it\",\"items\":[{\"name\":\"Margherita\",\"qty\":2,\"pricePaise\":45000}]}")
                .retrieve().body(JsonNode.class);
        String orderId = created.get("orderId").asString();
        assertThat(orderId).matches("ord-\\d{8}");
        assertThat(created.get("orderRef").asString()).isEqualTo(orderId.substring(orderId.length() - 4));
        assertThat(created.get("version").asInt()).isEqualTo(1);
        assertThat(created.get("valuePaise").asLong()).isEqualTo(90000);

        JsonNode accepted = post("/orders/" + orderId + "/accept", "");
        assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("version").asInt()).isEqualTo(2);
        assertThat(accepted.get("acceptedAt").asString()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z");

        JsonNode cancelled = post("/orders/" + orderId + "/cancel", "{\"initiatedBy\":\"CUSTOMER\"}");
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("version").asInt()).isEqualTo(3);
        assertThat(cancelled.get("cancelInitiator").asString()).isEqualTo("CUSTOMER");

        List<Message> spied = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Message next = rabbitTemplate.receive(spy.getName());
            if (next != null) {
                spied.add(next);
            }
            assertThat(spied).hasSize(3);
        });
        List<String> routingKeys = spied.stream().map(m -> m.getMessageProperties().getReceivedRoutingKey()).toList();
        assertThat(routingKeys).containsExactly("order.placed", "order.accepted", "order.cancelled");

        Message cancelledMessage = spied.get(2);
        JsonNode payload = jsonMapper.readTree(cancelledMessage.getBody());
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.get("type").asString()).isEqualTo("order.cancelled");
        assertThat(payload.get("orderId").asString()).isEqualTo(orderId);
        assertThat(payload.get("outletId").asString()).isEqualTo("outlet-it");
        assertThat(payload.get("orderVersion").asInt()).isEqualTo(3);
        assertThat(payload.get("initiatedBy").asString()).isEqualTo("CUSTOMER");
        assertThat(payload.get("runId").asString()).isEqualTo("run-it-1");
        assertThat(payload.get("synthetic").asBoolean()).isFalse();
        assertThat(payload.get("occurredAt").asString()).isEqualTo(cancelled.get("cancelledAt").asString());
        assertThat(payload.has("traceparent")).isFalse();
        assertThat(cancelledMessage.getMessageProperties().getMessageId())
                .isEqualTo(payload.get("eventId").asString());
        Map<String, Object> headers = cancelledMessage.getMessageProperties().getHeaders();
        assertThat(headers).containsKey("x-published-at");
        assertThat(cancelledMessage.getMessageProperties().getContentType()).isEqualTo("application/json");

        // the real queue (bound only to order.cancelled) holds exactly this one message
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(rabbitAdmin.getQueueInfo("nocook.cancellations").getMessageCount()).isEqualTo(1));
        Message queued = rabbitTemplate.receive("nocook.cancellations", 2000);
        assertThat(queued).isNotNull();
        assertThat(jsonMapper.readTree(queued.getBody()).get("orderId").asString()).isEqualTo(orderId);
        assertThat(rabbitTemplate.receive("nocook.cancellations")).isNull();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                        jdbc.sql("select count(*) from oms.outbox where status = 'PENDING'").query(Long.class).single())
                .isZero());
    }

    @Test
    void illegalTransitionsReturn409WithErrorBody() {
        String orderId = createOrder("outlet-it-409");
        post("/orders/" + orderId + "/cancel", "{\"initiatedBy\":\"CUSTOMER\"}");

        RestClient client = client();
        JsonNode error = client.post().uri("/orders/" + orderId + "/accept")
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(409));
                    return jsonMapper.readTree(response.getBody());
                });
        assertThat(error.get("error").asString()).isEqualTo("ILLEGAL_TRANSITION");
        assertThat(error.get("state").asString()).isEqualTo("CANCELLED");
        assertThat(error.get("orderId").asString()).isEqualTo(orderId);

        int missingStatus = client.get().uri("/orders/ord-99999999")
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(missingStatus).isEqualTo(404);
    }

    @Test
    void fullLifecycleStageListLoadAndReinstate() {
        String orderId = createOrder("outlet-it-full");
        post("/orders/" + orderId + "/accept", "");
        JsonNode staged = post("/orders/" + orderId + "/stage", "{\"stage\":\"OVEN\"}");
        assertThat(staged.get("status").asString()).isEqualTo("PREPARING");
        assertThat(staged.get("stage").asString()).isEqualTo("OVEN");
        JsonNode ready = post("/orders/" + orderId + "/ready", "");
        assertThat(ready.get("readyAt").isNull()).isFalse();
        assertThat(post("/orders/" + orderId + "/pickup", "").get("status").asString()).isEqualTo("PICKED_UP");
        assertThat(post("/orders/" + orderId + "/deliver", "").get("status").asString()).isEqualTo("DELIVERED");

        JsonNode active = client().get().uri("/outlets/outlet-it-full/orders?active=true").retrieve().body(JsonNode.class);
        assertThat(active.size()).isZero();
        JsonNode all = client().get().uri("/outlets/outlet-it-full/orders").retrieve().body(JsonNode.class);
        assertThat(all.size()).isEqualTo(1);

        JsonNode loaded = client().post().uri("/load/accepted-orders").header("content-type", "application/json")
                .header("X-Run-Id", "run-it-2")
                .body("{\"outletId\":\"outlet-it-load\",\"stage\":\"PREP\",\"acceptedSecondsAgo\":120,\"valuePaise\":45000}")
                .retrieve().body(JsonNode.class);
        assertThat(loaded.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(loaded.get("stage").asString()).isEqualTo("PREP");
        String loadedId = loaded.get("orderId").asString();
        assertThat(jdbc.sql("select run_id from oms.orders where id = :id").param("id", loadedId)
                        .query(String.class).single())
                .isEqualTo("run-it-2");

        post("/orders/" + loadedId + "/cancel", "{\"initiatedBy\":\"PLATFORM\"}");
        JsonNode reinstated = post("/orders/" + loadedId + "/reinstate", "");
        assertThat(reinstated.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(reinstated.get("cancelledAt").isNull()).isTrue();
        assertThat(reinstated.get("version").asInt()).isEqualTo(3);
    }
}
