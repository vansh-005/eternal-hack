package com.nocook.service;

import com.nocook.domain.model.CancelInitiator;
import com.nocook.domain.model.CancellationEvent;
import com.nocook.domain.model.OrderSnapshot;
import com.nocook.domain.model.OrderStatus;
import com.nocook.domain.oms.OmsView;
import com.nocook.service.application.CaseOpeningService.OpenResult;
import com.nocook.service.ingestion.CancellationIngestor;
import com.nocook.service.notifications.CardPublisher;
import com.nocook.service.oms.OmsReadPort;
import com.nocook.service.scheduling.DeadlineWorker;
import com.nocook.service.scheduling.LeaseReaper;
import com.nocook.service.scheduling.OutboxDispatcher;
import com.nocook.service.telephony.MockTelephonyGateway;
import com.nocook.service.telephony.TelephonyGateway;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared base of the B-03..B-06 integration tests (one Spring context, one Postgres, one RabbitMQ).
 * Schedulers are off: tests advance {@link B0xClock} and call {@code tick()} on the workers directly.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "nocook.scheduling.enabled=false",
            "nocook.mqtt.enabled=false",
            "nocook.ingest.oms-retry.max-total=0s",
            "nocook.telephony.self-base-url=",
            "nocook.telephony.auto-outcome-time-scale=0.05",
            "management.otlp.metrics.export.enabled=false",
            "management.opentelemetry.tracing.export.otlp.enabled=false",
            "management.opentelemetry.logging.export.otlp.enabled=false"
        })
@org.springframework.context.annotation.Import(B0xBaseIT.Doubles.class)
abstract class B0xBaseIT {

    static final Instant START = Instant.parse("2026-11-21T14:00:00.000Z");

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

    /** Settable clock. */
    static class B0xClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(START);

        void set(Instant instant) {
            now.set(instant);
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    /** OMS read seam: snapshots by order id, with a global "down" switch. */
    static class B0xFakeOms implements OmsReadPort {
        final Map<String, OrderSnapshot> snapshots = new ConcurrentHashMap<>();
        volatile boolean down;

        @Override
        public OmsView get(String orderId) {
            OrderSnapshot snapshot = snapshots.get(orderId);
            if (down || snapshot == null) {
                return new OmsView.Unavailable(down ? "down" : "unknown order");
            }
            return new OmsView.Available(snapshot);
        }
    }

    record Published(String kind, String outletId, JsonNode json) {}

    /** Records what would go to MQTT; can be told to fail. */
    static class B0xPublisher implements CardPublisher {
        final List<Published> published = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger failuresRemaining = new AtomicInteger();
        volatile boolean failAll;
        private final JsonMapper mapper = JsonMapper.builder().build();

        @Override
        public void publishCard(String outletId, String cardJson) {
            publish("card", outletId, cardJson);
        }

        @Override
        public void publishCall(String outletId, String callJson) {
            publish("call", outletId, callJson);
        }

        private void publish(String kind, String outletId, String json) {
            if (failAll || failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new CardPublishException("test failure", new RuntimeException("boom"));
            }
            published.add(new Published(kind, outletId, mapper.readTree(json)));
        }

        List<Published> cards() {
            synchronized (published) {
                return published.stream().filter(p -> p.kind().equals("card")).toList();
            }
        }

        List<Published> calls() {
            synchronized (published) {
                return published.stream().filter(p -> p.kind().equals("call")).toList();
            }
        }
    }

    /** Wraps the real mock gateway; counts placeCall attempts and can fail the next ones. */
    static class B0xGateway implements TelephonyGateway {
        private final MockTelephonyGateway delegate;
        final AtomicInteger placeCalls = new AtomicInteger();
        volatile boolean failPlaceCall;

        B0xGateway(MockTelephonyGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public CallResult placeCall(CallRequest request) {
            placeCalls.incrementAndGet();
            if (failPlaceCall) {
                throw new IllegalStateException("gateway timeout");
            }
            return delegate.placeCall(request);
        }

        @Override
        public void hangup(String providerCallId) {
            delegate.hangup(providerCallId);
        }
    }

    @TestConfiguration
    static class Doubles {
        @Bean
        @Primary
        B0xClock b0xClock() {
            return new B0xClock();
        }

        @Bean
        @Primary
        B0xFakeOms b0xFakeOms() {
            return new B0xFakeOms();
        }

        @Bean
        @Primary
        B0xPublisher b0xPublisher() {
            return new B0xPublisher();
        }

        @Bean
        @Primary
        B0xGateway b0xGateway(MockTelephonyGateway delegate) {
            return new B0xGateway(delegate);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    B0xClock clock;

    @Autowired
    B0xFakeOms oms;

    @Autowired
    B0xPublisher mqtt;

    @Autowired
    B0xGateway gateway;

    @Autowired
    CancellationIngestor ingestor;

    @Autowired
    DeadlineWorker deadlineWorker;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    LeaseReaper reaper;

    @Autowired
    MeterRegistry meters;

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger orderSeq = new AtomicInteger();

    @BeforeEach
    void resetState() {
        jdbc.sql("truncate nocook.case_events, nocook.deadlines, nocook.outbox, nocook.call_attempts,"
                        + " nocook.webhook_events, nocook.ops_events, nocook.cases")
                .update();
        clock.set(START);
        oms.snapshots.clear();
        oms.down = false;
        mqtt.published.clear();
        mqtt.failuresRemaining.set(0);
        mqtt.failAll = false;
        gateway.placeCalls.set(0);
        gateway.failPlaceCall = false;
    }

    // ---- fixtures ----------------------------------------------------------------------------------------

    /** Cancelled order of {@code outletId}, accepted 120 s before the cancel, cancelled {@code cancelledAgo} ago. */
    OrderSnapshot cancelledOrder(String outletId, Duration cancelledAgo, CancelInitiator initiator) {
        String orderId = "ord-it-" + orderSeq.incrementAndGet();
        Instant cancelledAt = clock.instant().minus(cancelledAgo);
        OrderSnapshot snapshot = new OrderSnapshot(orderId, outletId, 2, OrderStatus.CANCELLED, "PREP",
                cancelledAt.minusSeconds(120), null, null, cancelledAt, initiator, 45000, "4417");
        oms.snapshots.put(orderId, snapshot);
        return snapshot;
    }

    /** Opens an eligible case for a fresh order cancelled at the current clock instant. */
    UUID openCase(String outletId) {
        return openCase(outletId, Duration.ZERO);
    }

    UUID openCase(String outletId, Duration cancelledAgo) {
        OrderSnapshot snapshot = cancelledOrder(outletId, cancelledAgo, CancelInitiator.CUSTOMER);
        return ingest(snapshot).caseId();
    }

    OpenResult ingest(OrderSnapshot snapshot) {
        CancellationEvent event = new CancellationEvent(UUID.randomUUID().toString(), snapshot.orderId(),
                snapshot.outletId(), snapshot.version(), snapshot.cancelledAt(), snapshot.cancelInitiator(), null);
        return ingestor.ingest(event, null);
    }

    /** OMS now shows the order ready (the kitchen finished it). */
    void markReady(String orderId) {
        OrderSnapshot s = oms.snapshots.get(orderId);
        oms.snapshots.put(orderId, new OrderSnapshot(s.orderId(), s.outletId(), s.version(), s.status(), "READY",
                s.acceptedAt(), clock.instant(), null, s.cancelledAt(), s.cancelInitiator(), s.valuePaise(), s.orderRef()));
    }

    String orderIdOf(UUID caseId) {
        return jdbc.sql("select order_id from nocook.cases where id = :id").param("id", caseId).query(String.class).single();
    }

    // ---- DB reads ----------------------------------------------------------------------------------------

    String state(UUID caseId) {
        return jdbc.sql("select state from nocook.cases where id = :id").param("id", caseId).query(String.class).single();
    }

    List<String> factKinds(UUID caseId) {
        return jdbc.sql("select kind from nocook.case_events where case_id = :id order by server_at, id")
                .param("id", caseId).query(String.class).list();
    }

    long countFacts(UUID caseId, String kind) {
        return jdbc.sql("select count(*) from nocook.case_events where case_id = :id and kind = :k")
                .param("id", caseId).param("k", kind).query(Long.class).single();
    }

    String deadlineStatus(UUID caseId, String kind) {
        return jdbc.sql("select status from nocook.deadlines where case_id = :id and kind = :k")
                .param("id", caseId).param("k", kind).query(String.class).single();
    }

    List<Map<String, Object>> outboxRows(UUID caseId) {
        return jdbc.sql("select type, action_key, status, attempts, payload ->> 'kind' as kind from nocook.outbox"
                        + " where case_id = :id order by created_at, action_key")
                .param("id", caseId).query().listOfRows();
    }

    double counter(String name, String... tags) {
        var search = meters.find(name);
        for (int i = 0; i < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        var found = search.counters();
        return found.stream().mapToDouble(c -> c.count()).sum();
    }

    // ---- HTTP --------------------------------------------------------------------------------------------

    record Resp(int status, JsonNode body) {}

    Resp http(String method, String path, Map<String, String> headers, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            headers.forEach(builder::header);
            if (body != null) {
                builder.header("content-type", "application/json");
            }
            builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = response.body().isBlank() ? null : json.readTree(response.body());
            return new Resp(response.statusCode(), parsed);
        } catch (java.io.IOException | InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    Resp ack(UUID caseId, String outletId, String key, String choice) {
        return http("POST", "/api/cases/" + caseId + "/ack",
                Map.of("X-Outlet-Id", outletId, "X-Device-Id", "tab-1"),
                "{\"choice\":\"" + choice + "\",\"idempotencyKey\":\"" + key + "\"}");
    }
}
