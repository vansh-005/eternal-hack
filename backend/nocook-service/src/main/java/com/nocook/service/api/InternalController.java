package com.nocook.service.api;

import com.nocook.service.chaos.ChaosProperties;
import com.nocook.service.chaos.Halter;
import com.nocook.service.config.WorkerProperties;
import com.nocook.service.notifications.ListenerControl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API.md section 1.9. Reached replica to replica on the compose network; nginx does not route {@code /internal}.
 * Both endpoints need the {@code X-Internal-Token} header.
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private static final Logger log = LoggerFactory.getLogger(InternalController.class);
    private static final long HALT_DELAY_MILLIS = 100;

    private final ChaosProperties properties;
    private final Halter halter;
    private final ListenerControl listenerControl;
    private final String replicaId;

    public InternalController(
            ChaosProperties properties, Halter halter, ListenerControl listenerControl, WorkerProperties.Replica replica) {
        this.properties = properties;
        this.halter = halter;
        this.listenerControl = listenerControl;
        this.replicaId = replica.replicaId();
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestHeader(value = "X-Internal-Token", required = false) @Nullable String token) {
        requireToken(token);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", replicaId);
        body.put("up", true);
        body.put("listener", listenerControl.state());
        return body;
    }

    /** Answers 202 first, then halts the JVM (no shutdown hooks) a moment later. */
    @PostMapping("/halt")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> halt(@RequestHeader(value = "X-Internal-Token", required = false) @Nullable String token) {
        requireToken(token);
        log.warn("halt requested replica={}", replicaId);
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(HALT_DELAY_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            halter.halt();
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("halting", true);
        return body;
    }

    private void requireToken(@Nullable String token) {
        byte[] expected = properties.internalToken().getBytes(StandardCharsets.UTF_8);
        byte[] given = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, given)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Missing or wrong internal token");
        }
    }
}
