package com.nocook.service.oms;

import com.nocook.domain.model.CancelInitiator;
import com.nocook.domain.model.OrderSnapshot;
import com.nocook.domain.model.OrderStatus;
import com.nocook.domain.oms.OmsView;
import com.nocook.service.config.OmsClientProperties;
import com.nocook.service.observability.NocookMeters;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** RestClient implementation of {@link OmsReadPort}: GET {base-url}/oms/api/orders/{id}, 300 ms connect / 800 ms read. */
@Component
public class OmsReadClient implements OmsReadPort {

    private static final Logger log = LoggerFactory.getLogger(OmsReadClient.class);

    private final RestClient restClient;
    private final NocookMeters meters;
    private final Clock clock;

    public OmsReadClient(
            ObjectProvider<ObservationRegistry> observationRegistry,
            OmsClientProperties.Values properties,
            NocookMeters meters,
            Clock clock) {
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public OmsView get(String orderId) {
        return read(orderId, "open");
    }

    @Override
    public OmsView getForEscalation(String orderId) {
        return read(orderId, "escalation");
    }

    private OmsView read(String orderId, String path) {
        Instant startedAt = clock.instant();
        try {
            JsonNode body = restClient.get()
                    .uri("/oms/api/orders/{id}", orderId)
                    .retrieve()
                    .body(JsonNode.class);
            OrderSnapshot snapshot = toSnapshot(body);
            meters.omsRead("ok", path, Duration.between(startedAt, clock.instant()));
            return new OmsView.Available(snapshot);
        } catch (ResourceAccessException ex) {
            return failed("timeout", path, startedAt, orderId, ex);
        } catch (RestClientResponseException ex) {
            return failed("error", path, startedAt, orderId, ex);
        } catch (RuntimeException ex) {
            return failed("error", path, startedAt, orderId, ex);
        }
    }

    private OmsView failed(String outcome, String path, Instant startedAt, String orderId, RuntimeException ex) {
        meters.omsRead(outcome, path, Duration.between(startedAt, clock.instant()));
        log.warn("oms read failed orderId={} path={} outcome={} reason={}", orderId, path, outcome, ex.toString());
        return new OmsView.Unavailable(outcome + ": " + ex.getClass().getSimpleName());
    }

    private static OrderSnapshot toSnapshot(@Nullable JsonNode body) {
        if (body == null || body.isNull()) {
            throw new IllegalStateException("empty OMS body");
        }
        return new OrderSnapshot(
                body.required("orderId").asString(),
                body.required("outletId").asString(),
                body.required("version").asInt(),
                OrderStatus.valueOf(body.required("status").asString()),
                body.required("stage").asString(),
                instant(body, "acceptedAt"),
                instant(body, "readyAt"),
                instant(body, "pickedUpAt"),
                instant(body, "cancelledAt"),
                initiator(body),
                body.required("valuePaise").asLong(),
                body.required("orderRef").asString());
    }

    private static @Nullable Instant instant(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return Instant.parse(value.asString());
    }

    private static @Nullable CancelInitiator initiator(JsonNode body) {
        JsonNode value = body.get("cancelInitiator");
        if (value == null || value.isNull()) {
            return null;
        }
        return CancelInitiator.valueOf(value.asString());
    }
}
