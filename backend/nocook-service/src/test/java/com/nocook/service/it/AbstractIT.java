package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.domain.model.CancelInitiator;
import com.nocook.domain.model.CancellationEvent;
import com.nocook.domain.model.OrderSnapshot;
import com.nocook.domain.model.OrderStatus;
import com.nocook.service.application.CaseOpeningService.OpenResult;
import com.nocook.service.ingestion.CancellationIngestor;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Shared harness (TESTING.md section 2): one Postgres 18 and one RabbitMQ per JVM, schema created by the real
 * Flyway migration running as {@code nocook_app}, scheduling off, MutableClock + FakeOms + RecordingCardPublisher.
 *
 * Deviation: containers are wired with {@code @DynamicPropertySource} (NOCOOK_DB_URL, RABBIT_*) instead of
 * {@code @ServiceConnection}, because a service connection would override the datasource user with the container
 * superuser, and the ITs must run as the restricted {@code nocook_app} role (R11).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "nocook.scheduling.enabled=false",
            "management.otlp.metrics.export.enabled=false",
            "management.opentelemetry.tracing.export.otlp.enabled=false",
            "management.opentelemetry.logging.export.otlp.enabled=false"
        })
@Import(TestClockConfig.class)
public abstract class AbstractIT {

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
            .withDatabaseName("nocook")
            .withUsername("postgres")
            .withPassword("postgres")
            .withCommand("postgres", "-c", "track_commit_timestamp=on")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("init.sql"), "/docker-entrypoint-initdb.d/01-init.sql");

    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("nocook")
            .withAdminPassword("nocook");

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("NOCOOK_DB_URL", AbstractIT::jdbcUrl);
        registry.add("RABBIT_HOST", RABBIT::getHost);
        registry.add("RABBIT_PORT", RABBIT::getAmqpPort);
    }

    static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/nocook";
    }

    @LocalServerPort
    protected int port;

    @Autowired protected JdbcClient jdbc;
    @Autowired protected MutableClock clock;
    @Autowired protected FakeOms fakeOms;
    @Autowired protected RecordingCardPublisher cards;
    @Autowired protected CancellationIngestor ingestor;
    @Autowired protected MeterRegistry meters;

    @BeforeEach
    void resetState() {
        jdbc.sql("truncate nocook.case_events, nocook.deadlines, nocook.outbox, nocook.call_attempts,"
                        + " nocook.webhook_events, nocook.ops_events, nocook.cases")
                .update();
        clock.set(TestClockConfig.T0);
        fakeOms.reset();
        cards.reset();
    }

    /** Runs SQL as the container superuser (used only to prepare the oms schema for R11). */
    protected static void asSuperuser(String sql) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), "postgres", "postgres");
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---- scenario helpers ----

    /** A cancelled order accepted 2 minutes before now, cancelled {@code cancelledAgo} ago, version 1. */
    protected OrderSnapshot cancelledOrder(String orderId, Duration cancelledAgo) {
        Instant now = clock.instant();
        OrderSnapshot snapshot = new OrderSnapshot(orderId, "outlet-it", 1, OrderStatus.CANCELLED, "PREP",
                now.minus(Duration.ofMinutes(2)), null, null, now.minus(cancelledAgo), CancelInitiator.CUSTOMER,
                45000, "4417");
        fakeOms.put(snapshot);
        return snapshot;
    }

    protected CancellationEvent eventFor(OrderSnapshot snapshot) {
        return new CancellationEvent(UUID.randomUUID().toString(), snapshot.orderId(), snapshot.outletId(),
                snapshot.version(), snapshot.cancelledAt(), CancelInitiator.CUSTOMER, null);
    }

    /** Opens one eligible case through the real ingestor. cancelledAt = now - 500 ms. */
    protected UUID openEligibleCase(String orderId) {
        OrderSnapshot snapshot = cancelledOrder(orderId, Duration.ofMillis(500));
        OpenResult result = ingestor.ingest(eventFor(snapshot), clock.instant());
        assertThat(result.outcome()).isEqualTo(com.nocook.service.application.CaseOpeningService.OpenOutcome.OPENED);
        return result.caseId();
    }

    protected static String amqpBody(OrderSnapshot snapshot, String eventId) {
        return "{\"schemaVersion\":1,\"eventId\":\"" + eventId + "\",\"type\":\"order.cancelled\","
                + "\"orderId\":\"" + snapshot.orderId() + "\",\"outletId\":\"" + snapshot.outletId() + "\","
                + "\"orderVersion\":" + snapshot.version() + ",\"occurredAt\":\"" + snapshot.cancelledAt() + "\","
                + "\"initiatedBy\":\"CUSTOMER\",\"runId\":null,\"synthetic\":false}";
    }

    protected long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    protected String stateOf(UUID caseId) {
        return jdbc.sql("select state from nocook.cases where id = :id").param("id", caseId)
                .query(String.class).single();
    }

    protected List<String> factKinds(UUID caseId) {
        return jdbc.sql("select kind from nocook.case_events where case_id = :id order by server_at, id")
                .param("id", caseId).query(String.class).list();
    }

    protected long factCount(String kind) {
        return jdbc.sql("select count(*) from nocook.case_events where kind = :k").param("k", kind)
                .query(Long.class).single();
    }

    protected double counter(String name, String... tags) {
        io.micrometer.core.instrument.Counter found = meters.find(name).tags(tags).counter();
        return found == null ? 0 : found.count();
    }
}
