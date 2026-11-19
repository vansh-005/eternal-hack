package com.nocook.service.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/** Typed views of the {@code nocook.*} blocks of BACKEND.md section 3.5 that the workers and adapters read. */
@Configuration
@EnableConfigurationProperties({
    WorkerProperties.Replica.class,
    WorkerProperties.Deadlines.class,
    WorkerProperties.Outbox.class,
    WorkerProperties.Mqtt.class,
    WorkerProperties.Telephony.class,
    WorkerProperties.OmsRetry.class,
    com.nocook.service.chaos.ChaosProperties.class
})
public class WorkerProperties {

    @ConfigurationProperties("nocook")
    public record Replica(@DefaultValue("local") String replicaId) {}

    @ConfigurationProperties("nocook.deadlines")
    public record Deadlines(
            @DefaultValue("250ms") Duration tick, @DefaultValue("50") int batch, @DefaultValue("10s") Duration lease) {}

    @ConfigurationProperties("nocook.outbox")
    public record Outbox(
            @DefaultValue("50ms") Duration tick, @DefaultValue("100") int batch, @DefaultValue("10s") Duration lease) {}

    @ConfigurationProperties("nocook.mqtt")
    public record Mqtt(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("mosquitto") String host,
            @DefaultValue("1883") int port,
            @DefaultValue("2s") Duration publishTimeout) {}

    /**
     * {@code selfBaseUrl} may be blank in tests: the mock provider then falls back to
     * {@code http://localhost:${local.server.port}}. {@code autoOutcomeTimeScale} multiplies every script delay.
     */
    @ConfigurationProperties("nocook.telephony")
    public record Telephony(
            @DefaultValue("dev-secret") String secret,
            @DefaultValue("http://localhost:8080") String selfBaseUrl,
            @DefaultValue("outlet-load-") String autoOutcomeOutletPrefix,
            @DefaultValue("1.0") double autoOutcomeTimeScale) {}

    /**
     * Listener-level backoff while OMS is unreachable (see CancellationListener). Sleeps start at {@code initial},
     * double up to {@code max}, and stop after {@code maxTotal} of cumulative sleeping; only then is the delivery
     * requeued. {@code maxTotal=0} disables the in-listener retries.
     */
    @ConfigurationProperties("nocook.ingest.oms-retry")
    public record OmsRetry(
            @DefaultValue("70s") Duration maxTotal,
            @DefaultValue("500ms") Duration initial,
            @DefaultValue("2s") Duration max) {}
}
