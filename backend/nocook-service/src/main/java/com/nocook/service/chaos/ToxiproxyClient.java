package com.nocook.service.chaos;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** Toxiproxy HTTP API (INFRA.md section 4): the postgres latency toxic, the oms proxy switch and reset. */
@Component
public class ToxiproxyClient {

    public static final String DB_PROXY = "postgres";
    public static final String OMS_PROXY = "oms";
    public static final String DB_TOXIC = "db_latency";

    private final RestClient client;

    public ToxiproxyClient(ChaosProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder().baseUrl(properties.toxiproxyUrl()).requestFactory(factory).build();
    }

    /** ms above 0 adds or updates the downstream latency toxic; 0 removes it. */
    public void setDbLatency(int ms) {
        try {
            if (ms <= 0) {
                removeDbLatency();
                return;
            }
            String attributes = "{\"latency\":" + ms + ",\"jitter\":20}";
            try {
                post("/proxies/" + DB_PROXY + "/toxics", "{\"name\":\"" + DB_TOXIC
                        + "\",\"type\":\"latency\",\"stream\":\"downstream\",\"attributes\":" + attributes + "}");
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() != 409) {
                    throw ex;
                }
                post("/proxies/" + DB_PROXY + "/toxics/" + DB_TOXIC, "{\"attributes\":" + attributes + "}");
            }
        } catch (RestClientException ex) {
            throw new ChaosException("toxiproxy db-latency failed", ex);
        }
    }

    private void removeDbLatency() {
        try {
            client.delete().uri("/proxies/{proxy}/toxics/{toxic}", DB_PROXY, DB_TOXIC).retrieve().toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 404) {
                throw ex;
            }
        }
    }

    public void setOmsEnabled(boolean enabled) {
        try {
            post("/proxies/" + OMS_PROXY, "{\"enabled\":" + enabled + "}");
        } catch (RestClientException ex) {
            throw new ChaosException("toxiproxy oms switch failed", ex);
        }
    }

    /** Removes every toxic and enables every proxy. */
    public void reset() {
        try {
            post("/reset", "{}");
        } catch (RestClientException ex) {
            throw new ChaosException("toxiproxy reset failed", ex);
        }
    }

    /** Current db latency in ms (0 when there is no toxic). */
    public int dbLatencyMs() {
        try {
            JsonNode toxics = client.get().uri("/proxies/{proxy}/toxics", DB_PROXY).retrieve().body(JsonNode.class);
            if (toxics != null && toxics.isArray()) {
                for (JsonNode toxic : toxics) {
                    if (DB_TOXIC.equals(toxic.path("name").asString())) {
                        return toxic.path("attributes").path("latency").asInt(0);
                    }
                }
            }
            return 0;
        } catch (RestClientException ex) {
            throw new ChaosException("toxiproxy read failed", ex);
        }
    }

    public boolean omsDown() {
        try {
            JsonNode proxy = client.get().uri("/proxies/{proxy}", OMS_PROXY).retrieve().body(JsonNode.class);
            return proxy != null && !proxy.path("enabled").asBoolean(true);
        } catch (RestClientException ex) {
            throw new ChaosException("toxiproxy read failed", ex);
        }
    }

    private void post(String path, String json) {
        client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(json).retrieve().toBodilessEntity();
    }
}
