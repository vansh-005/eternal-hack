package com.nocook.service.api;

import com.nocook.domain.event.CaseEvent;
import com.nocook.domain.model.AckChannel;
import com.nocook.domain.model.AckChoice;
import com.nocook.service.application.AckService;
import com.nocook.service.application.AckService.AckResult;
import com.nocook.service.config.Iso;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/cases/{caseId}/ack (API.md 1.1). Identity is the X-Outlet-Id / X-Device-Id header pair (ADR-012). */
@RestController
@RequestMapping("/api/cases")
public class AckController {

    public record AckRequest(
            @NotNull AckChoice choice, @NotBlank String idempotencyKey, @Nullable Integer caseVersionSeen) {}

    private final AckService ackService;

    public AckController(AckService ackService) {
        this.ackService = ackService;
    }

    @PostMapping("/{caseId}/ack")
    public ResponseEntity<Map<String, Object>> ack(
            @PathVariable String caseId,
            @RequestHeader(value = "X-Outlet-Id", required = false) @Nullable String outletId,
            @RequestHeader(value = "X-Device-Id", required = false) @Nullable String deviceId,
            @Valid @RequestBody AckRequest request) {
        UUID id = CaseIds.parse(caseId);
        String actor = deviceId == null || deviceId.isBlank() ? "unknown" : deviceId;
        // A missing outlet header can never match a case, so it is reported as OUTLET_MISMATCH.
        String outlet = outletId == null ? "" : outletId;
        CaseEvent.MerchantAck ack =
                new CaseEvent.MerchantAck(request.choice(), AckChannel.TABLET, actor, request.idempotencyKey());
        AckResult result = ackService.ack(id, outlet, ack, request.caseVersionSeen());
        return switch (result) {
            case AckResult.Acked acked -> ResponseEntity.ok(ackedBody(acked));
            case AckResult.NotFound notFound -> throw new ApiException(
                    HttpStatus.NOT_FOUND, "CASE_NOT_FOUND", "No such case").with("caseId", id.toString());
            case AckResult.OutletMismatch mismatch -> throw new ApiException(
                    HttpStatus.FORBIDDEN, "OUTLET_MISMATCH", "Case belongs to another outlet")
                    .with("caseId", id.toString());
            case AckResult.Rejected rejected -> throw rejection(rejected);
        };
    }

    private static Map<String, Object> ackedBody(AckResult.Acked acked) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseId", acked.caseId().toString());
        body.put("state", "ACKNOWLEDGED");
        body.put("choice", acked.choice().name());
        body.put("channel", acked.channel().name());
        body.put("ackedAt", Iso.format(acked.ackedAt()));
        body.put("duplicate", acked.duplicate());
        return body;
    }

    private static ApiException rejection(AckResult.Rejected rejected) {
        String message = "ALREADY_ACKNOWLEDGED".equals(rejected.code())
                ? "Case was already acknowledged"
                : "Case is " + rejected.state();
        ApiException exception = new ApiException(HttpStatus.CONFLICT, rejected.code(), message)
                .with("caseId", rejected.caseId().toString())
                .with("state", rejected.state().name())
                .with("closeReason", rejected.closeReason() == null ? null : rejected.closeReason().name());
        if ("ALREADY_ACKNOWLEDGED".equals(rejected.code())) {
            exception.with("ackChoice", rejected.ackChoice() == null ? null : rejected.ackChoice().name())
                    .with("ackedAt", rejected.ackedAt() == null ? null : Iso.format(rejected.ackedAt()));
        }
        return exception;
    }
}
