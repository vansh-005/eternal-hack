package com.nocook.service.chaos;

import com.nocook.service.api.ApiException;
import com.nocook.service.chaos.ReplicaClient.ReplicaStatus;
import com.nocook.service.config.WorkerProperties;
import com.nocook.service.notifications.ControlChannel;
import com.nocook.service.notifications.ListenerControl;
import com.nocook.service.observability.NocookMeters;
import com.nocook.service.persistence.OpsEventRepository;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import com.nocook.service.config.RabbitTopologyConfig;
import org.slf4j.Logger;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * API.md section 1.6. Every action: do the thing, insert {@code nocook.ops_events}, write a Grafana annotation (tags
 * {@code ["chaos", action]}, text = the request body JSON) and count {@code nocook.chaos{action}}. Also records run
 * markers for the run orchestrator (RUN_START / RUN_END, annotation tags {@code ["run", profile, runId]}).
 */
@Service
public class ChaosService {

    private static final Logger log = LoggerFactory.getLogger(ChaosService.class);
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final int LISTENER_WAIT_STEPS = 50;
    private static final long LISTENER_WAIT_STEP_MILLIS = 100;

    public record ChaosState(int dbLatencyMs, boolean omsDown, String listener) {}

    private final ToxiproxyClient toxiproxy;
    private final GrafanaAnnotator annotator;
    private final ReplicaClient replicas;
    private final ListenerControl listenerControl;
    private final ControlChannel controlChannel;
    private final OpsEventRepository opsEvents;
    private final NocookMeters meters;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final String selfId;
    private final RestClient omsClient;
    private final ObjectProvider<RabbitAdmin> rabbitAdmin;
    private volatile ChaosState lastKnown = new ChaosState(0, false, ListenerControl.RUNNING);

    public ChaosService(
            ToxiproxyClient toxiproxy,
            GrafanaAnnotator annotator,
            ReplicaClient replicas,
            ListenerControl listenerControl,
            ControlChannel controlChannel,
            OpsEventRepository opsEvents,
            NocookMeters meters,
            JsonMapper jsonMapper,
            Clock clock,
            ChaosProperties properties,
            WorkerProperties.Replica replica,
            ObjectProvider<RabbitAdmin> rabbitAdmin) {
        this.rabbitAdmin = rabbitAdmin;
        this.toxiproxy = toxiproxy;
        this.annotator = annotator;
        this.replicas = replicas;
        this.listenerControl = listenerControl;
        this.controlChannel = controlChannel;
        this.opsEvents = opsEvents;
        this.meters = meters;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.selfId = replica.replicaId();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.omsClient = RestClient.builder().baseUrl(properties.omsUrl()).requestFactory(factory).build();
    }

    /** Executes one chaos action and returns the time recorded for it. */
    public Instant apply(String action, JsonNode requestBody) {
        JsonNode body = requestBody == null || requestBody.isNull() || requestBody.isMissingNode()
                ? jsonMapper.createObjectNode()
                : requestBody;
        switch (action) {
            case "db-latency" -> dbLatency(body);
            case "oms-down" -> omsDown(body);
            case "listener" -> listener(body);
            case "kill-replica" -> {
                return killReplica(body);
            }
            case "duplicate-storm" -> duplicateStorm(body);
            case "reset" -> reset();
            default -> throw new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_ACTION", "No such chaos action")
                    .with("action", action);
        }
        return record(action, body);
    }

    /** Current fault state; falls back to the last known values when Toxiproxy cannot be read. */
    public ChaosState state() {
        int latency = lastKnown.dbLatencyMs();
        boolean omsDown = lastKnown.omsDown();
        try {
            latency = toxiproxy.dbLatencyMs();
            omsDown = toxiproxy.omsDown();
        } catch (ChaosException ex) {
            log.warn("chaos state: toxiproxy unreadable, using last known reason={}", ex.getMessage());
        }
        ChaosState state = new ChaosState(latency, omsDown, aggregateListener(replicas.statuses()));
        lastKnown = state;
        return state;
    }

    /** RUN_START / RUN_END marker for the run orchestrator (annotation tags run, profile, runId). */
    public Instant runMarker(String kind, String runId, String profile) {
        if (!"RUN_START".equals(kind) && !"RUN_END".equals(kind)) {
            throw validation("kind must be RUN_START or RUN_END");
        }
        if (runId == null || !SAFE_TOKEN.matcher(runId).matches()) {
            throw validation("runId is required (letters, digits, . _ -)");
        }
        if (profile == null || !SAFE_TOKEN.matcher(profile).matches()) {
            throw validation("profile is required (letters, digits, . _ -)");
        }
        Instant at = clock.instant();
        ObjectNode metadata = jsonMapper.createObjectNode();
        metadata.put("runId", runId);
        metadata.put("profile", profile);
        opsEvents.insert(kind, at, selfId, jsonMapper.writeValueAsString(metadata));
        annotator.annotate(kind + " " + profile + " " + runId, List.of("run", profile, runId));
        return at;
    }

    private void dbLatency(JsonNode body) {
        int ms = intField(body, "ms", null, 0, 60_000);
        toxiproxy.setDbLatency(ms);
    }

    private void omsDown(JsonNode body) {
        JsonNode down = body.path("down");
        if (!down.isBoolean()) {
            throw validation("down must be true or false");
        }
        toxiproxy.setOmsEnabled(!down.asBoolean());
    }

    private void listener(JsonNode body) {
        String state = body.path("state").asString("");
        if (!ListenerControl.RUNNING.equals(state) && !ListenerControl.STOPPED.equals(state)) {
            throw validation("state must be RUNNING or STOPPED");
        }
        setListenerEverywhere(state);
    }

    private Instant killReplica(JsonNode body) {
        String target = body.path("replica").asString("");
        if (!replicas.isKnown(target)) {
            throw validation("replica must be one of " + replicas.replicaIds());
        }
        // Recorded first: if the target is this very replica the response never gets written.
        Instant at = record("kill-replica", body);
        replicas.halt(target);
        return at;
    }

    private void duplicateStorm(JsonNode body) {
        int lastN = intField(body, "lastN", 50, 1, 1_000);
        int times = intField(body, "times", 20, 1, 100);
        try {
            omsClient.post()
                    .uri("/oms/api/test/replay-cancellations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"lastN\":" + lastN + ",\"times\":" + times + "}")
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw new ChaosException("replay-cancellations failed", ex);
        }
    }

    private void reset() {
        ChaosException failure = null;
        try {
            toxiproxy.reset();
        } catch (ChaosException ex) {
            failure = ex;
        }
        setListenerEverywhere(ListenerControl.RUNNING);
        if (failure != null) {
            throw failure;
        }
    }

    /** Applies the state here, broadcasts it, then waits (up to 5 s) until every reachable replica reports it. */
    private void setListenerEverywhere(String desired) {
        listenerControl.apply(desired);
        String command = ListenerControl.STOPPED.equals(desired)
                ? ControlChannel.LISTENER_STOP
                : ControlChannel.LISTENER_START;
        if (!controlChannel.broadcast(command)) {
            log.warn("control channel unavailable, listener change applied on this replica only");
            return;
        }
        for (int step = 0; step < LISTENER_WAIT_STEPS; step++) {
            if (allReachableReplicasAre(desired) && brokerAgrees(desired)) {
                return;
            }
            sleep(LISTENER_WAIT_STEP_MILLIS);
        }
        log.warn("listener state not confirmed on every replica desired={}", desired);
    }

    /** Broker-side confirmation (passive declare, not the delayed management stats): consumers 0 when stopped. */
    private boolean brokerAgrees(String desired) {
        RabbitAdmin admin = rabbitAdmin.getIfAvailable();
        if (admin == null) {
            return true;
        }
        try {
            QueueInformation info = admin.getQueueInfo(RabbitTopologyConfig.CANCELLATIONS_QUEUE);
            if (info == null) {
                return true;
            }
            boolean noConsumers = info.getConsumerCount() == 0;
            return ListenerControl.STOPPED.equals(desired) == noConsumers;
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private boolean allReachableReplicasAre(String desired) {
        for (ReplicaStatus status : replicas.statuses()) {
            if (status.up() && !desired.equals(status.listener())) {
                return false;
            }
        }
        return true;
    }

    private static String aggregateListener(List<ReplicaStatus> statuses) {
        for (ReplicaStatus status : statuses) {
            if (status.up() && ListenerControl.STOPPED.equals(status.listener())) {
                return ListenerControl.STOPPED;
            }
        }
        return ListenerControl.RUNNING;
    }

    private Instant record(String action, JsonNode body) {
        Instant at = clock.instant();
        String bodyJson = jsonMapper.writeValueAsString(body);
        opsEvents.insert(action, at, selfId, bodyJson);
        annotator.annotate(bodyJson, List.of("chaos", action));
        meters.chaos(action);
        log.info("chaos action={} body={}", action, bodyJson);
        return at;
    }

    private static int intField(JsonNode body, String name, Integer fallback, int min, int max) {
        JsonNode node = body.path(name);
        if (node.isMissingNode() || node.isNull()) {
            if (fallback == null) {
                throw validation(name + " is required");
            }
            return fallback;
        }
        if (!node.isInt()) {
            throw validation(name + " must be an integer");
        }
        int value = node.asInt();
        if (value < min || value > max) {
            throw validation(name + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", ex);
        }
    }
}
