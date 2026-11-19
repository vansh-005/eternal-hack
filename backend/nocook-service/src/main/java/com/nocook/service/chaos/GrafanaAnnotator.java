package com.nocook.service.chaos;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** OBSERVABILITY.md section 6: {@code POST /api/annotations} {time, tags, text} with basic auth. Best effort. */
@Component
public class GrafanaAnnotator {

    private static final Logger log = LoggerFactory.getLogger(GrafanaAnnotator.class);

    private final RestClient client;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public GrafanaAnnotator(ChaosProperties properties, JsonMapper jsonMapper, Clock clock) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.client = RestClient.builder()
                .baseUrl(properties.grafanaUrl())
                .requestFactory(factory)
                .defaultHeaders(headers -> headers.setBasicAuth(properties.grafanaUser(), properties.grafanaPassword()))
                .build();
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** Returns false (and logs) when Grafana is unreachable: an annotation must never fail the chaos action. */
    public boolean annotate(String text, List<String> tags) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("time", clock.millis());
        ArrayNode tagNodes = body.putArray("tags");
        tags.forEach(tagNodes::add);
        body.put("text", text);
        try {
            client.post().uri("/api/annotations").contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(body)).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException ex) {
            log.warn("grafana annotation failed tags={} reason={}", tags, ex.toString());
            return false;
        }
    }
}
