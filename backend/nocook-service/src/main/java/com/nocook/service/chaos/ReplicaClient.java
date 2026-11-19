package com.nocook.service.chaos;

import com.nocook.service.config.WorkerProperties;
import com.nocook.service.notifications.ListenerControl;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Talks to the other replicas over their internal endpoints: {@code GET /internal/status} (is it up, what is its
 * listener doing) and {@code POST /internal/halt}. The replica's own entry is answered locally. A replica that does not
 * answer within 500 ms is reported {@code up=false}.
 */
@Component
public class ReplicaClient {

    public static final String TOKEN_HEADER = "X-Internal-Token";

    private static final Logger log = LoggerFactory.getLogger(ReplicaClient.class);

    public record ReplicaStatus(String id, boolean up, String listener) {}

    private final ChaosProperties properties;
    private final ListenerControl listenerControl;
    private final String selfId;
    private final RestClient client;

    public ReplicaClient(
            ChaosProperties properties, ListenerControl listenerControl, WorkerProperties.Replica replica) {
        this.properties = properties;
        this.listenerControl = listenerControl;
        this.selfId = replica.replicaId();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(800));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public List<String> replicaIds() {
        return properties.replicas();
    }

    public boolean isKnown(String replicaId) {
        return properties.replicas().contains(replicaId);
    }

    public List<ReplicaStatus> statuses() {
        List<CompletableFuture<ReplicaStatus>> futures = new ArrayList<>();
        for (String id : properties.replicas()) {
            futures.add(CompletableFuture.supplyAsync(() -> statusOf(id), Thread::startVirtualThread));
        }
        List<ReplicaStatus> statuses = new ArrayList<>();
        for (CompletableFuture<ReplicaStatus> future : futures) {
            statuses.add(future.join());
        }
        return statuses;
    }

    private ReplicaStatus statusOf(String id) {
        if (id.equals(selfId)) {
            return new ReplicaStatus(id, true, listenerControl.state());
        }
        try {
            JsonNode body = client.get()
                    .uri(baseUrl(id) + "/internal/status")
                    .header(TOKEN_HEADER, properties.internalToken())
                    .retrieve()
                    .body(JsonNode.class);
            String listener = body == null ? "UNKNOWN" : body.path("listener").asString("UNKNOWN");
            return new ReplicaStatus(id, true, listener);
        } catch (RestClientException ex) {
            return new ReplicaStatus(id, false, "UNKNOWN");
        }
    }

    /**
     * Asks the replica to halt. A dropped connection counts as success: the JVM may die before it answers. Any HTTP
     * error status (wrong token, ...) is a failure.
     */
    public void halt(String id) {
        try {
            client.post()
                    .uri(baseUrl(id) + "/internal/halt")
                    .header(TOKEN_HEADER, properties.internalToken())
                    .retrieve()
                    .toBodilessEntity();
        } catch (ResourceAccessException ex) {
            log.info("halt forwarded, connection dropped as expected replica={}", id);
        } catch (RestClientException ex) {
            throw new ChaosException("halt of " + id + " failed", ex);
        }
    }

    private String baseUrl(String id) {
        return String.format(properties.replicaUrlTemplate(), id);
    }
}
