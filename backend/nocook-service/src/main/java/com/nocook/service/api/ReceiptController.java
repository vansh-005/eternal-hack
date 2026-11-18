package com.nocook.service.api;

import com.nocook.service.application.ReceiptService;
import com.nocook.service.application.ReceiptService.ReceiptKind;
import com.nocook.service.application.ReceiptService.ReceiptResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/cases/{caseId}/receipts (API.md 1.2). Always 202 for a known case; repeats are ignored. */
@RestController
@RequestMapping("/api/cases")
public class ReceiptController {

    public record ReceiptRequest(
            @NotNull ReceiptKind kind, @NotBlank String deviceId, @NotBlank String messageId, @Nullable String deviceAt) {}

    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    @PostMapping("/{caseId}/receipts")
    public ResponseEntity<Map<String, Object>> receipt(
            @PathVariable String caseId, @Valid @RequestBody ReceiptRequest request) {
        UUID id = CaseIds.parse(caseId);
        ReceiptResult result = receiptService.record(
                id, request.kind(), request.deviceId(), request.messageId(), parseInstant(request.deviceAt()));
        if (result == ReceiptResult.CASE_NOT_FOUND) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND", "No such case").with("caseId", id.toString());
        }
        return ResponseEntity.accepted().body(Map.of("recorded", result == ReceiptResult.RECORDED));
    }

    private static @Nullable Instant parseInstant(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ex) {
            return null; // deviceAt is informational only
        }
    }
}
