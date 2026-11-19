package com.nocook.service.chaos;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code nocook.chaos.*} (BACKEND.md section 3.5). {@code replicas} are the compose service names of every nocook
 * replica; {@code replicaUrlTemplate} turns one into its base URL (port 8080 inside the compose network). Only these
 * names are ever contacted, so kill-replica cannot be pointed at an arbitrary host.
 */
@ConfigurationProperties("nocook.chaos")
public record ChaosProperties(
        @DefaultValue("http://toxiproxy:8474") String toxiproxyUrl,
        @DefaultValue("http://lgtm:3000") String grafanaUrl,
        @DefaultValue("admin") String grafanaUser,
        @DefaultValue("admin") String grafanaPassword,
        @DefaultValue("dev-internal") String internalToken,
        @DefaultValue({"nocook-1", "nocook-2", "nocook-3"}) List<String> replicas,
        @DefaultValue("http://%s:8080") String replicaUrlTemplate,
        @DefaultValue("http://mock-oms:8090") String omsUrl) {}
