package com.nocook.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.service.chaos.Halter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * B-08: the chaos endpoints against a fake Toxiproxy + Grafana + OMS + second replica (one JDK HttpServer), the
 * ops read endpoints, run markers and the internal halt endpoint. The listener container is the real one.
 */
class ChaosControllerIT extends B0xBaseIT {

    record Call(String method, String path, String body, String authorization, String token) {}

    /** Toxiproxy, Grafana, mock OMS and a second replica in one fake. */
    static final class FakeDeps {
        final HttpServer server;
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final AtomicBoolean toxicPresent = new AtomicBoolean();
        final AtomicInteger toxicLatency = new AtomicInteger();
        final AtomicBoolean omsEnabled = new AtomicBoolean(true);

        FakeDeps() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
            server.createContext("/", this::handle);
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            calls.add(new Call(method, path, body, exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-Internal-Token")));
            int status = 200;
            String response = "{}";
            if (path.equals("/proxies/postgres/toxics") && method.equals("POST")) {
                if (toxicPresent.get()) {
                    status = 409;
                } else {
                    toxicPresent.set(true);
                    toxicLatency.set(extractLatency(body));
                }
            } else if (path.equals("/proxies/postgres/toxics/db_latency") && method.equals("POST")) {
                toxicLatency.set(extractLatency(body));
            } else if (path.equals("/proxies/postgres/toxics/db_latency") && method.equals("DELETE")) {
                status = toxicPresent.getAndSet(false) ? 204 : 404;
                response = "";
            } else if (path.equals("/proxies/postgres/toxics") && method.equals("GET")) {
                response = toxicPresent.get()
                        ? "[{\"name\":\"db_latency\",\"attributes\":{\"latency\":" + toxicLatency.get() + "}}]"
                        : "[]";
            } else if (path.equals("/proxies/oms") && method.equals("POST")) {
                omsEnabled.set(body.contains("true"));
            } else if (path.equals("/proxies/oms") && method.equals("GET")) {
                response = "{\"enabled\":" + omsEnabled.get() + "}";
            } else if (path.equals("/reset")) {
                toxicPresent.set(false);
                omsEnabled.set(true);
            } else if (path.equals("/internal/status")) {
                response = "{\"id\":\"127.0.0.1\",\"up\":true,\"listener\":\"RUNNING\"}";
            } else if (path.equals("/internal/halt")) {
                status = 202;
            } else if (path.equals("/oms/api/test/replay-cancellations")) {
                response = "{\"republished\":1000}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        }

        private static int extractLatency(String body) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"latency\":(\\d+)").matcher(body);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
        }

        List<Call> callsTo(String method, String path) {
            return calls.stream().filter(c -> c.method().equals(method) && c.path().equals(path)).toList();
        }
    }

    static final FakeDeps FAKE = new FakeDeps();
    static final Path RESULTS = createResultsDir();

    static Path createResultsDir() {
        try {
            Path root = Files.createTempDirectory("results-it");
            Path run = Files.createDirectories(root.resolve("run-20261121-1930-P2"));
            Files.writeString(run.resolve("summary.json"),
                    "{\"runId\":\"run-20261121-1930-P2\",\"profile\":\"P2\",\"startedAt\":\"2026-11-21T19:30:00.000Z\","
                            + "\"verdict\":\"PASS\",\"latency\":{}}");
            Files.writeString(run.resolve("manifest.json"), "{\"runId\":\"run-20261121-1930-P2\"}");
            Files.createDirectories(root.resolve("sim-20261121-0100"));
            Files.writeString(root.resolve("sim-20261121-0100").resolve("sim-summary.json"), "{\"n\":10000}");
            Files.writeString(root.resolve("tests-20261121-0100.json"), "{}");
            return root;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void chaosProperties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + FAKE.port();
        registry.add("nocook.chaos.toxiproxy-url", () -> base);
        registry.add("nocook.chaos.grafana-url", () -> base);
        registry.add("nocook.chaos.oms-url", () -> base);
        registry.add("nocook.chaos.replicas", () -> "local,127.0.0.1");
        registry.add("nocook.chaos.replica-url-template", () -> "http://%s:" + FAKE.port());
        registry.add("nocook.results-dir", RESULTS::toString);
    }

    static final class RecordingHalter extends Halter {
        final AtomicInteger halts = new AtomicInteger();

        @Override
        public void halt() {
            halts.incrementAndGet();
        }
    }

    @TestConfiguration
    static class HalterConfig {
        @Bean
        @Primary
        RecordingHalter recordingHalter() {
            return new RecordingHalter();
        }
    }

    @Autowired RecordingHalter halter;
    @Autowired org.springframework.amqp.rabbit.core.RabbitAdmin rabbitAdmin;

    @BeforeEach
    void resetFake() {
        FAKE.calls.clear();
        FAKE.toxicPresent.set(false);
        FAKE.omsEnabled.set(true);
        halter.halts.set(0);
    }

    private Resp chaos(String action, String body) {
        return http("POST", "/api/ops/chaos/" + action, Map.of(), body);
    }

    @Test
    void db_latency_creates_updates_and_removes_the_toxic_and_records_everything() {
        Resp created = chaos("db-latency", "{\"ms\":300}");
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.body().get("ok").asBoolean()).isTrue();
        assertThat(created.body().get("at").asString()).isEqualTo("2026-11-21T14:00:00.000Z");
        assertThat(FAKE.toxicPresent.get()).isTrue();
        assertThat(FAKE.toxicLatency.get()).isEqualTo(300);

        chaos("db-latency", "{\"ms\":500}"); // second POST gets 409 and updates the existing toxic
        assertThat(FAKE.toxicLatency.get()).isEqualTo(500);
        assertThat(http("GET", "/api/ops/chaos/state", Map.of(), null).body().get("dbLatencyMs").asInt()).isEqualTo(500);

        chaos("db-latency", "{\"ms\":0}");
        assertThat(FAKE.toxicPresent.get()).isFalse();

        assertThat(jdbc.sql("select count(*) from nocook.ops_events where kind = 'db-latency'")
                        .query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("select metadata ->> 'ms' from nocook.ops_events order by id limit 1")
                        .query(String.class).single()).isEqualTo("300");
        List<Call> annotations = FAKE.callsTo("POST", "/api/annotations");
        assertThat(annotations).hasSize(3);
        assertThat(annotations.get(0).authorization()).startsWith("Basic ");
        JsonNode annotation = json.readTree(annotations.get(0).body());
        assertThat(annotation.get("tags").get(0).asString()).isEqualTo("chaos");
        assertThat(annotation.get("tags").get(1).asString()).isEqualTo("db-latency");
        assertThat(annotation.get("text").asString()).contains("\"ms\":300");
        assertThat(counter("nocook.chaos", "action", "db-latency")).isEqualTo(3);
    }

    @Test
    void oms_down_switches_the_oms_proxy() {
        chaos("oms-down", "{\"down\":true}");
        assertThat(FAKE.omsEnabled.get()).isFalse();
        assertThat(http("GET", "/api/ops/chaos/state", Map.of(), null).body().get("omsDown").asBoolean()).isTrue();
        chaos("oms-down", "{\"down\":false}");
        assertThat(FAKE.omsEnabled.get()).isTrue();
    }

    @Test
    void listener_stop_and_start_move_the_real_consumers_and_reset_restarts_them() {
        chaos("listener", "{\"state\":\"STOPPED\"}");
        assertThat(rabbitAdmin.getQueueInfo("nocook.cancellations").getConsumerCount()).isZero();
        Resp summary = http("GET", "/api/ops/summary", Map.of(), null);
        assertThat(summary.body().get("replicas").get(0).get("id").asString()).isEqualTo("local");
        assertThat(summary.body().get("replicas").get(0).get("listener").asString()).isEqualTo("STOPPED");
        assertThat(http("GET", "/api/ops/chaos/state", Map.of(), null).body().get("listener").asString())
                .isEqualTo("STOPPED");

        chaos("listener", "{\"state\":\"RUNNING\"}");
        assertThat(rabbitAdmin.getQueueInfo("nocook.cancellations").getConsumerCount()).isPositive();

        chaos("listener", "{\"state\":\"STOPPED\"}");
        Resp reset = chaos("reset", "{}");
        assertThat(reset.status()).isEqualTo(200);
        assertThat(FAKE.callsTo("POST", "/reset")).hasSize(1);
        assertThat(rabbitAdmin.getQueueInfo("nocook.cancellations").getConsumerCount()).isPositive();
    }

    @Test
    void kill_replica_forwards_halt_with_the_token_and_rejects_unknown_replicas() {
        assertThat(chaos("kill-replica", "{\"replica\":\"nobody\"}").status()).isEqualTo(400);
        assertThat(FAKE.callsTo("POST", "/internal/halt")).isEmpty();

        Resp response = chaos("kill-replica", "{\"replica\":\"127.0.0.1\"}");

        assertThat(response.status()).isEqualTo(200);
        List<Call> halts = FAKE.callsTo("POST", "/internal/halt");
        assertThat(halts).hasSize(1);
        assertThat(halts.get(0).token()).isEqualTo("dev-internal");
        assertThat(jdbc.sql("select count(*) from nocook.ops_events where kind = 'kill-replica'")
                        .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void duplicate_storm_calls_replay_with_defaults_and_validation_rejects_bad_bodies() {
        assertThat(chaos("duplicate-storm", "{}").status()).isEqualTo(200);
        List<Call> replays = FAKE.callsTo("POST", "/oms/api/test/replay-cancellations");
        assertThat(replays).hasSize(1);
        assertThat(replays.get(0).body()).isEqualTo("{\"lastN\":50,\"times\":20}");

        assertThat(chaos("db-latency", "{\"ms\":-5}").status()).isEqualTo(400);
        assertThat(chaos("db-latency", "{}").status()).isEqualTo(400);
        assertThat(chaos("oms-down", "{\"down\":\"yes\"}").status()).isEqualTo(400);
        assertThat(chaos("listener", "{\"state\":\"PAUSED\"}").status()).isEqualTo(400);
        assertThat(chaos("explode", "{}").status()).isEqualTo(404);
    }

    @Test
    void internal_endpoints_need_the_token_and_halt_answers_202_then_halts() throws Exception {
        assertThat(http("POST", "/internal/halt", Map.of(), null).status()).isEqualTo(403);
        assertThat(http("POST", "/internal/halt", Map.of("X-Internal-Token", "wrong"), null).status()).isEqualTo(403);
        assertThat(http("GET", "/internal/status", Map.of(), null).status()).isEqualTo(403);
        assertThat(halter.halts.get()).isZero();

        Resp status = http("GET", "/internal/status", Map.of("X-Internal-Token", "dev-internal"), null);
        assertThat(status.body().get("listener").asString()).isEqualTo("RUNNING");

        Resp halt = http("POST", "/internal/halt", Map.of("X-Internal-Token", "dev-internal"), null);
        assertThat(halt.status()).isEqualTo(202);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(() -> halter.halts.get() == 1);
    }

    @Test
    void run_marker_writes_an_ops_event_and_a_run_annotation() {
        Resp response = http("POST", "/api/ops/run-marker", Map.of(),
                "{\"kind\":\"RUN_START\",\"runId\":\"run-20261121-1930-P3\",\"profile\":\"P3\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(jdbc.sql("select metadata ->> 'runId' from nocook.ops_events where kind = 'RUN_START'")
                        .query(String.class).single()).isEqualTo("run-20261121-1930-P3");
        JsonNode annotation = json.readTree(FAKE.callsTo("POST", "/api/annotations").get(0).body());
        assertThat(annotation.get("tags").get(0).asString()).isEqualTo("run");
        assertThat(annotation.get("tags").get(1).asString()).isEqualTo("P3");
        assertThat(annotation.get("tags").get(2).asString()).isEqualTo("run-20261121-1930-P3");
        assertThat(http("POST", "/api/ops/run-marker", Map.of(),
                        "{\"kind\":\"RUN_MIDDLE\",\"runId\":\"x\",\"profile\":\"P3\"}").status()).isEqualTo(400);
    }

    @Test
    void ops_cases_filters_and_pages_with_a_keyset_cursor() {
        UUID first = openCase("outlet-a");
        clock.advance(java.time.Duration.ofSeconds(1));
        UUID second = openCase("outlet-b");
        clock.advance(java.time.Duration.ofSeconds(1));
        UUID third = openCase("outlet-a");
        ack(second, "outlet-b", "k", "STOPPED");

        JsonNode all = http("GET", "/api/ops/cases?limit=2", Map.of(), null).body();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).get("caseId").asString()).isEqualTo(third.toString());
        assertThat(all.get(1).get("caseId").asString()).isEqualTo(second.toString());
        assertThat(all.get(1).get("ackChoice").asString()).isEqualTo("STOPPED");
        assertThat(all.get(1).get("closedAt").asString()).isNotBlank();
        assertThat(all.get(0).get("outletId").asString()).isEqualTo("outlet-a");

        JsonNode next = http("GET", "/api/ops/cases?limit=2&before=" + second, Map.of(), null).body();
        assertThat(next).hasSize(1);
        assertThat(next.get(0).get("caseId").asString()).isEqualTo(first.toString());

        JsonNode byOutlet = http("GET", "/api/ops/cases?outlet=outlet-b", Map.of(), null).body();
        assertThat(byOutlet).hasSize(1);
        JsonNode byState = http("GET", "/api/ops/cases?state=AWAITING_ACK", Map.of(), null).body();
        assertThat(byState).hasSize(2);
        assertThat(http("GET", "/api/ops/cases?state=BOGUS", Map.of(), null).status()).isEqualTo(400);
    }

    @Test
    void ops_summary_counts_cases_and_lists_replicas() {
        openCase("outlet-a");
        UUID acked = openCase("outlet-a");
        ack(acked, "outlet-a", "k", "STOPPED");

        JsonNode summary = http("GET", "/api/ops/summary", Map.of(), null).body();

        assertThat(summary.get("openCases").asInt()).isEqualTo(1);
        assertThat(summary.get("openedLast5m").asInt()).isEqualTo(2);
        assertThat(summary.get("ackedLast5m").asInt()).isEqualTo(1);
        assertThat(summary.get("expiredLast5m").asInt()).isZero();
        assertThat(summary.get("replicas")).hasSize(2);
        assertThat(summary.get("replicas").get(1).get("id").asString()).isEqualTo("127.0.0.1");
        assertThat(summary.get("replicas").get(1).get("up").asBoolean()).isTrue();
        assertThat(summary.has("queueReady")).isTrue();
        assertThat(summary.has("dlqReady")).isTrue();
    }

    @Test
    void runs_and_simulation_are_read_from_the_results_directory() {
        JsonNode runs = http("GET", "/api/ops/runs", Map.of(), null).body();
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("runId").asString()).isEqualTo("run-20261121-1930-P2");
        assertThat(runs.get(0).get("verdict").asString()).isEqualTo("PASS");

        JsonNode run = http("GET", "/api/ops/runs/run-20261121-1930-P2", Map.of(), null).body();
        assertThat(run.get("summary").get("profile").asString()).isEqualTo("P2");
        assertThat(run.get("manifest").get("runId").asString()).isEqualTo("run-20261121-1930-P2");
        assertThat(run.get("invariants").isNull()).isTrue();
        assertThat(http("GET", "/api/ops/runs/nope", Map.of(), null).status()).isEqualTo(404);

        assertThat(http("GET", "/api/ops/simulations/latest", Map.of(), null).body().get("n").asInt()).isEqualTo(10000);
    }
}
