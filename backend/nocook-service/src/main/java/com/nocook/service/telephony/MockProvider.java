package com.nocook.service.telephony;

import com.nocook.service.config.Iso;
import com.nocook.service.config.WorkerProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The simulated provider side: builds a signed webhook body and POSTs it to this app's own webhook endpoints, so the
 * path is the same as a real provider round-trip (API.md 1.7). Used by {@code MockProviderController} (a human on the
 * tablet) and by the auto-outcome scripts (load outlets).
 */
@Component
public class MockProvider {

    private static final Logger log = LoggerFactory.getLogger(MockProvider.class);

    private final WebhookSigner signer;
    private final WorkerProperties.Telephony telephony;
    private final Environment environment;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public MockProvider(
            WebhookSigner signer,
            WorkerProperties.Telephony telephony,
            Environment environment,
            JsonMapper jsonMapper,
            Clock clock) {
        this.signer = signer;
        this.telephony = telephony;
        this.environment = environment;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** Status webhook on its own virtual thread. */
    public void emitStatusAsync(String providerCallId, String status) {
        Thread.startVirtualThread(() -> emitStatus(providerCallId, status));
    }

    /** DTMF webhook followed by COMPLETED (API.md 1.7 dtmf), on its own virtual thread. */
    public void emitDtmfThenCompletedAsync(String providerCallId, String digit) {
        Thread.startVirtualThread(() -> {
            emitDtmf(providerCallId, digit);
            emitStatus(providerCallId, "COMPLETED");
        });
    }

    public int emitStatus(String providerCallId, String status) {
        ObjectNode body = baseBody(providerCallId);
        body.put("status", status);
        return post("/api/webhooks/telephony/status", body);
    }

    public int emitDtmf(String providerCallId, String digit) {
        ObjectNode body = baseBody(providerCallId);
        body.put("digits", digit);
        return post("/api/webhooks/telephony/dtmf", body);
    }

    private ObjectNode baseBody(String providerCallId) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("providerCallId", providerCallId);
        body.put("providerEventId", UUID.randomUUID().toString());
        body.put("at", Iso.format(clock.instant()));
        return body;
    }

    private int post(String path, ObjectNode body) {
        byte[] raw = jsonMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(5))
                .header("content-type", "application/json")
                .header(WebhookSigner.SIGNATURE_HEADER, signer.sign(raw))
                .POST(HttpRequest.BodyPublishers.ofByteArray(raw))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (IOException ex) {
            log.warn("mock provider webhook failed path={} reason={}", path, ex.toString());
            return -1;
        }
    }

    private String baseUrl() {
        String configured = telephony.selfBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return "http://localhost:" + environment.getProperty("local.server.port", "8080");
    }
}
