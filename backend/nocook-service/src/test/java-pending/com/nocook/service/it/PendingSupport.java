package com.nocook.service.it;

import com.nocook.service.scheduling.DeadlineWorker;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Helpers for the not-yet-enabled R-tests. Adjust the two marked methods when main code lands. */
final class PendingSupport {

    static final String WEBHOOK_SECRET = "dev-secret";
    static final JsonMapper JSON = JsonMapper.builder().build();

    record Reply(int status, JsonNode body) {}

    private PendingSupport() {}

    /** ADJUST: construct a second DeadlineWorker with its own worker id, same collaborators as the bean. */
    static DeadlineWorker newWorker(ApplicationContext context, String workerId) {
        return context.getBean(DeadlineWorker.class).withWorkerId(workerId);
    }

    static RestClient http(int port) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }

    static Reply ack(RestClient http, UUID caseId, String outletId, String choice, String idempotencyKey) {
        return http.post().uri("/api/cases/{id}/ack", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Outlet-Id", outletId)
                .header("X-Device-Id", "it-device")
                .body("{\"choice\":\"" + choice + "\",\"idempotencyKey\":\"" + idempotencyKey + "\"}")
                .exchange((request, response) -> new Reply(response.getStatusCode().value(),
                        JSON.readTree(response.getBody().readAllBytes())));
    }

    static Reply webhook(RestClient http, String kind, String body, String signatureOrNull) {
        RestClient.RequestBodySpec spec = http.post().uri("/api/webhooks/telephony/{kind}", kind)
                .contentType(MediaType.APPLICATION_JSON);
        if (signatureOrNull != null) {
            spec = spec.header("X-Mock-Signature", signatureOrNull);
        }
        return spec.body(body).exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            JsonNode json = bytes.length == 0 ? JSON.createObjectNode() : JSON.readTree(bytes);
            return new Reply(response.getStatusCode().value(), json);
        });
    }

    static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
