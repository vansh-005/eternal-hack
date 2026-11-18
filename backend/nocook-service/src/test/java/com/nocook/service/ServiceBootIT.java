package com.nocook.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.otlp.metrics.export.enabled=false",
            "management.opentelemetry.tracing.export.otlp.enabled=false",
            "management.opentelemetry.logging.export.otlp.enabled=false"
        })
class ServiceBootIT {

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

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RabbitAdmin rabbitAdmin;

    @Test
    void bootsRunsFlywayDeclaresTopologyAndReportsHealthy() {
        JsonNode health = RestClient.create("http://localhost:" + port).get().uri("/actuator/health")
                .retrieve().body(JsonNode.class);
        assertThat(health.get("status").asString()).isEqualTo("UP");

        Long tables = jdbc.sql("select count(*) from information_schema.tables where table_schema = 'nocook'"
                        + " and table_name in ('cases','case_events','deadlines','outbox','call_attempts',"
                        + "'webhook_events','ops_events')")
                .query(Long.class).single();
        assertThat(tables).isEqualTo(7);

        assertThat(rabbitAdmin.getQueueInfo("nocook.cancellations")).isNotNull();
        assertThat(rabbitAdmin.getQueueInfo("nocook.cancellations.dlq")).isNotNull();
    }
}
