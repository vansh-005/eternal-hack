package com.nocook.service.api;

import com.nocook.domain.model.CaseState;
import com.nocook.service.chaos.ChaosService;
import com.nocook.service.chaos.ReplicaClient;
import com.nocook.service.chaos.ReplicaClient.ReplicaStatus;
import com.nocook.service.config.Iso;
import com.nocook.service.config.RabbitTopologyConfig;
import com.nocook.service.persistence.OpsQueryRepository;
import com.nocook.service.persistence.OpsQueryRepository.Cursor;
import com.nocook.service.persistence.OpsQueryRepository.OpsCaseRow;
import com.nocook.service.persistence.OpsQueryRepository.SummaryCounts;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * API.md section 1.5 (ops read) plus {@code POST /api/ops/run-marker} (an addition for the run orchestrator, see
 * DECISIONS.md): records RUN_START / RUN_END in ops_events and as a Grafana annotation tagged run, profile, runId.
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private static final int MAX_LIMIT = 500;

    private final OpsQueryRepository queries;
    private final ReplicaClient replicas;
    private final ChaosService chaos;
    private final ResultsReader results;
    private final ObjectProvider<RabbitAdmin> rabbitAdmin;
    private final Clock clock;

    public OpsController(
            OpsQueryRepository queries,
            ReplicaClient replicas,
            ChaosService chaos,
            ResultsReader results,
            ObjectProvider<RabbitAdmin> rabbitAdmin,
            Clock clock) {
        this.queries = queries;
        this.replicas = replicas;
        this.chaos = chaos;
        this.results = results;
        this.rabbitAdmin = rabbitAdmin;
        this.clock = clock;
    }

    /**
     * Newest-first page. {@code before} is a case id (keyset on cancelled_at, id: pass the last item of the previous
     * page) or an ISO-8601 instant (cancelled_at strictly before it).
     */
    @GetMapping("/cases")
    public List<Map<String, Object>> cases(
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable String outlet,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) @Nullable String before) {
        if (state != null && !isCaseState(state)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Unknown state");
        }
        int boundedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Map<String, Object>> body = new ArrayList<>();
        for (OpsCaseRow row : queries.findCases(state, outlet, boundedLimit, parseCursor(before))) {
            body.add(caseBody(row));
        }
        return body;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        SummaryCounts counts = queries.summaryCounts(clock.instant());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("openCases", counts.openCases());
        body.put("openedLast5m", counts.openedLast5m());
        body.put("ackedLast5m", counts.ackedLast5m());
        body.put("expiredLast5m", counts.expiredLast5m());
        body.put("queueReady", queueReady(RabbitTopologyConfig.CANCELLATIONS_QUEUE));
        body.put("dlqReady", queueReady(RabbitTopologyConfig.CANCELLATIONS_DLQ));
        body.put("outboxPending", counts.outboxPending());
        body.put("deadlinesOverdue", counts.deadlinesOverdue());
        List<Map<String, Object>> replicaBodies = new ArrayList<>();
        for (ReplicaStatus status : replicas.statuses()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", status.id());
            item.put("up", status.up());
            item.put("listener", status.listener());
            replicaBodies.add(item);
        }
        body.put("replicas", replicaBodies);
        return body;
    }

    @GetMapping("/runs")
    public List<JsonNode> runs() {
        return results.runHeaders();
    }

    @GetMapping("/runs/{runId}")
    public JsonNode run(@PathVariable String runId) {
        return results.run(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "No such run")
                        .with("runId", runId));
    }

    @GetMapping("/simulations/latest")
    public JsonNode latestSimulation() {
        return results.latestSimulation()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SIMULATION_NOT_FOUND", "No simulation yet"));
    }

    @GetMapping("/chaos/state")
    public Map<String, Object> chaosState() {
        ChaosService.ChaosState state = chaos.state();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dbLatencyMs", state.dbLatencyMs());
        body.put("omsDown", state.omsDown());
        body.put("listener", state.listener());
        return body;
    }

    @PostMapping("/run-marker")
    public Map<String, Object> runMarker(@RequestBody JsonNode request) {
        Instant at = chaos.runMarker(
                request.path("kind").asString(""),
                request.path("runId").asString(""),
                request.path("profile").asString(""));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("at", Iso.format(at));
        return body;
    }

    private long queueReady(String queue) {
        RabbitAdmin admin = rabbitAdmin.getIfAvailable();
        if (admin == null) {
            return 0;
        }
        QueueInformation info = admin.getQueueInfo(queue);
        return info == null ? 0 : info.getMessageCount();
    }

    private static boolean isCaseState(String state) {
        for (CaseState known : CaseState.values()) {
            if (known.name().equals(state)) {
                return true;
            }
        }
        return false;
    }

    private @Nullable Cursor parseCursor(@Nullable String before) {
        if (before == null || before.isBlank()) {
            return null;
        }
        try {
            UUID caseId = UUID.fromString(before);
            return queries.cursorOf(caseId).orElseThrow(
                    () -> new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Unknown before case"));
        } catch (IllegalArgumentException notAUuid) {
            try {
                return new Cursor(Instant.parse(before), null);
            } catch (DateTimeParseException notAnInstant) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "before must be a case id or ISO instant");
            }
        }
    }

    private static Map<String, Object> caseBody(OpsCaseRow row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("caseId", row.caseId().toString());
        item.put("orderId", row.orderId());
        item.put("orderRef", row.orderRef());
        item.put("outletId", row.outletId());
        item.put("state", row.state());
        item.put("caseVersion", row.caseVersion());
        item.put("cancelledAt", Iso.format(row.cancelledAt()));
        item.put("openedAt", Iso.format(row.openedAt()));
        item.put("closedAt", row.closedAt() == null ? null : Iso.format(row.closedAt()));
        item.put("closeReason", row.closeReason());
        item.put("ackChoice", row.ackChoice());
        item.put("ackChannel", row.ackChannel());
        item.put("runId", row.runId());
        return item;
    }
}
