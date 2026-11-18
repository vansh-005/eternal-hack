package com.nocook.service.config;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HiveMQ MQTT 5 async client, client id {@code nocook-${REPLICA_ID}}, clean start, automatic reconnect (0.5 s to 5 s).
 * Disabled with {@code nocook.mqtt.enabled=false} (tests supply their own CardPublisher).
 */
@Configuration
@ConditionalOnProperty(name = "nocook.mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class MqttConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttConfig.class);

    @Bean
    public Mqtt5AsyncClient mqttClient(WorkerProperties.Mqtt mqtt, WorkerProperties.Replica replica) {
        return MqttClient.builder()
                .useMqttVersion5()
                .identifier("nocook-" + replica.replicaId())
                .serverHost(mqtt.host())
                .serverPort(mqtt.port())
                .automaticReconnect()
                .initialDelay(500, TimeUnit.MILLISECONDS)
                .maxDelay(5, TimeUnit.SECONDS)
                .applyAutomaticReconnect()
                .addConnectedListener(ctx -> log.info("mqtt connected host={}", mqtt.host()))
                .addDisconnectedListener(ctx -> log.warn("mqtt disconnected cause={}", ctx.getCause().toString()))
                .buildAsync();
    }

    @Bean
    public com.nocook.service.notifications.CardPublisher mqttCardPublisher(
            Mqtt5AsyncClient client, WorkerProperties.Mqtt mqtt) {
        return new com.nocook.service.notifications.MqttCardPublisher(client, mqtt);
    }

    /** Non-blocking first connect; the publisher re-triggers a connect if the client is idle-disconnected. */
    @Bean
    public ApplicationRunner mqttConnectOnStartup(Mqtt5AsyncClient client) {
        return args -> com.nocook.service.notifications.MqttCardPublisher.connect(client);
    }
}
