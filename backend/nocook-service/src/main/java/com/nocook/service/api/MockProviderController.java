package com.nocook.service.api;

import com.nocook.service.telephony.MockProvider;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The simulated provider's control surface (API.md 1.7): the tablet call screen presses buttons here and the provider
 * answers with a signed webhook, asynchronously. It deliberately holds no call state: any replica can serve it.
 */
@RestController
@RequestMapping("/api/mock-telephony/calls")
public class MockProviderController {

    public record DtmfRequest(@Nullable String digit) {}

    private final MockProvider provider;

    public MockProviderController(MockProvider provider) {
        this.provider = provider;
    }

    @PostMapping("/{providerCallId}/{action}")
    public ResponseEntity<Map<String, Object>> act(
            @PathVariable String providerCallId,
            @PathVariable String action,
            @RequestBody(required = false) @Nullable DtmfRequest body) {
        switch (action) {
            case "answer" -> provider.emitStatusAsync(providerCallId, "ANSWERED");
            case "reject", "fail" -> provider.emitStatusAsync(providerCallId, "FAILED");
            case "busy" -> provider.emitStatusAsync(providerCallId, "BUSY");
            case "no-answer" -> provider.emitStatusAsync(providerCallId, "NO_ANSWER");
            case "dtmf" -> {
                if (body == null || body.digit() == null || body.digit().isBlank()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "digit is required");
                }
                provider.emitDtmfThenCompletedAsync(providerCallId, body.digit());
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Unknown action: " + action);
        }
        return ResponseEntity.accepted().body(Map.of("accepted", true, "action", action));
    }
}
