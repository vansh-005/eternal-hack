package com.nocook.service.api;

import com.nocook.service.config.Iso;
import com.nocook.service.persistence.CaseQueryRepository;
import com.nocook.service.persistence.CaseQueryRepository.CaseHeader;
import com.nocook.service.persistence.CaseQueryRepository.EventRow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** GET /api/cases/{caseId}/timeline (API.md 1.4). {@code derived.*Ms} are milliseconds from cancelledAt. */
@RestController
@RequestMapping("/api/cases")
public class TimelineController {

    private final CaseQueryRepository queries;
    private final JsonMapper jsonMapper;

    public TimelineController(CaseQueryRepository queries, JsonMapper jsonMapper) {
        this.queries = queries;
        this.jsonMapper = jsonMapper;
    }

    @GetMapping("/{caseId}/timeline")
    public Map<String, Object> timeline(@PathVariable String caseId) {
        UUID id = CaseIds.parse(caseId);
        CaseHeader header = queries.findHeader(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND", "No such case")
                        .with("caseId", id.toString()));
        List<EventRow> events = queries.findEvents(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("case", caseBody(header));
        body.put("events", eventsBody(events));
        body.put("derived", derived(header, events));
        return body;
    }

    private static Map<String, Object> caseBody(CaseHeader header) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("caseId", header.caseId().toString());
        item.put("orderId", header.orderId());
        item.put("orderRef", header.orderRef());
        item.put("outletId", header.outletId());
        item.put("state", header.state());
        item.put("caseVersion", header.caseVersion());
        item.put("cancelledAt", Iso.format(header.cancelledAt()));
        item.put("openedAt", Iso.format(header.openedAt()));
        item.put("closedAt", iso(header.closedAt()));
        item.put("closeReason", header.closeReason());
        item.put("ackChoice", header.ackChoice());
        item.put("ackChannel", header.ackChannel());
        item.put("runId", header.runId());
        return item;
    }

    private List<Map<String, Object>> eventsBody(List<EventRow> events) {
        List<Map<String, Object>> body = new ArrayList<>();
        for (EventRow event : events) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("at", Iso.format(event.at()));
            item.put("kind", event.kind());
            item.put("channel", event.channel());
            item.put("deviceId", event.deviceId());
            item.put("metadata", jsonMapper.readTree(event.metadataJson()));
            item.put("traceId", event.traceId());
            body.add(item);
        }
        return body;
    }

    private static Map<String, Object> derived(CaseHeader header, List<EventRow> events) {
        Instant cancelledAt = header.cancelledAt();
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("caseOpenMs", millis(cancelledAt, header.openedAt()));
        derived.put("firstDeliveredMs", millis(cancelledAt, firstAt(events, "CARD_DELIVERED")));
        derived.put("firstRenderedMs", millis(cancelledAt, firstAt(events, "CARD_RENDERED")));
        derived.put("ackMs", millis(cancelledAt, header.ackedAt()));
        return derived;
    }

    private static @Nullable Instant firstAt(List<EventRow> events, String kind) {
        for (EventRow event : events) {
            if (kind.equals(event.kind())) {
                return event.at();
            }
        }
        return null;
    }

    private static @Nullable Long millis(Instant from, @Nullable Instant to) {
        return to == null ? null : Duration.between(from, to).toMillis();
    }

    private static @Nullable String iso(@Nullable Instant instant) {
        return instant == null ? null : Iso.format(instant);
    }
}
