package com.nocook.service.api;

import com.nocook.domain.model.Timings;
import com.nocook.service.config.Iso;
import com.nocook.service.persistence.CaseQueryRepository;
import com.nocook.service.persistence.CaseQueryRepository.OpenCaseRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/outlets/{outletId}/open-cases (API.md 1.3): used by tablets on load and on every MQTT reconnect. */
@RestController
@RequestMapping("/api/outlets")
public class OutletController {

    private final CaseQueryRepository queries;
    private final Timings timings;

    public OutletController(CaseQueryRepository queries, Timings timings) {
        this.queries = queries;
        this.timings = timings;
    }

    @GetMapping("/{outletId}/open-cases")
    public List<Map<String, Object>> openCases(@PathVariable String outletId) {
        List<Map<String, Object>> body = new ArrayList<>();
        for (OpenCaseRow row : queries.findOpenByOutlet(outletId)) {
            body.add(toBody(row));
        }
        return body;
    }

    private Map<String, Object> toBody(OpenCaseRow row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("caseId", row.caseId().toString());
        item.put("orderId", row.orderId());
        item.put("orderRef", row.orderRef());
        item.put("orderVersion", row.orderVersion());
        item.put("state", row.state());
        item.put("caseVersion", row.caseVersion());
        item.put("cancelledAt", Iso.format(row.cancelledAt()));
        item.put("openedAt", Iso.format(row.openedAt()));
        item.put("l1At", Iso.format(row.cancelledAt().plus(timings.l1())));
        item.put("ivrAt", Iso.format(row.cancelledAt().plus(timings.ivr())));
        item.put("expiresAt", Iso.format(row.cancelledAt().plus(timings.maxAge())));
        if (row.providerCallId() == null) {
            item.put("call", null);
        } else {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("providerCallId", row.providerCallId());
            call.put("status", row.callStatus());
            item.put("call", call);
        }
        return item;
    }
}
