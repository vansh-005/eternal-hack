package com.nocook.service.notifications;

import com.hivemq.client.mqtt.MqttClientState;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.nocook.service.config.Iso;
import com.nocook.service.config.MqttConnectHooks;
import com.nocook.service.config.WorkerProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MQTT topic {@code nocook/control} (API.md section 4): the ops API broadcasts LISTENER_STOP / LISTENER_START and every
 * replica (the sender included, since MQTT echoes to the publisher's own subscription) applies it to its own listener
 * container. Without an MQTT client (tests) {@link #broadcast} does nothing and callers apply locally.
 */
@Component
public class ControlChannel {

    public static final String TOPIC = "nocook/control";
    public static final String LISTENER_STOP = "LISTENER_STOP";
    public static final String LISTENER_START = "LISTENER_START";

    private static final Logger log = LoggerFactory.getLogger(ControlChannel.class);

    private final ObjectProvider<Mqtt5AsyncClient> clientProvider;
    private final ObjectProvider<MqttConnectHooks> hooksProvider;
    private final ListenerControl listenerControl;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final String replicaId;

    public ControlChannel(
            ObjectProvider<Mqtt5AsyncClient> clientProvider,
            ObjectProvider<MqttConnectHooks> hooksProvider,
            ListenerControl listenerControl,
            JsonMapper jsonMapper,
            Clock clock,
            WorkerProperties.Replica replica) {
        this.clientProvider = clientProvider;
        this.hooksProvider = hooksProvider;
        this.listenerControl = listenerControl;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.replicaId = replica.replicaId();
    }

    @jakarta.annotation.PostConstruct
    void subscribeOnConnect() {
        Mqtt5AsyncClient client = clientProvider.getIfAvailable();
        MqttConnectHooks hooks = hooksProvider.getIfAvailable();
        if (client == null || hooks == null) {
            return;
        }
        hooks.register(() -> subscribe(client));
        if (client.getState() == MqttClientState.CONNECTED) {
            subscribe(client);
        }
    }

    private void subscribe(Mqtt5AsyncClient client) {
        client.subscribeWith()
                .topicFilter(TOPIC)
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback(this::onMessage)
                .send()
                .whenComplete((ack, error) -> {
                    if (error != null) {
                        log.warn("control subscribe failed reason={}", error.toString());
                    }
                });
    }

    /** Publishes the command to every replica. Returns false when there is no connected MQTT client. */
    public boolean broadcast(String command) {
        Mqtt5AsyncClient client = clientProvider.getIfAvailable();
        if (client == null || client.getState() != MqttClientState.CONNECTED) {
            return false;
        }
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("command", command);
        body.put("at", Iso.format(clock.instant()));
        body.put("by", replicaId);
        client.publishWith()
                .topic(TOPIC)
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(false)
                .payload(jsonMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8))
                .send();
        return true;
    }

    private void onMessage(Mqtt5Publish publish) {
        String command;
        try {
            JsonNode node = jsonMapper.readTree(new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8));
            command = node.path("command").asString();
        } catch (RuntimeException ex) {
            log.warn("control message ignored reason={}", ex.toString());
            return;
        }
        // stop() waits for in-flight deliveries: never block the MQTT callback thread.
        Thread.startVirtualThread(() -> handle(command));
    }

    void handle(String command) {
        log.info("control command received command={}", command);
        if (LISTENER_STOP.equals(command)) {
            listenerControl.stop();
        } else if (LISTENER_START.equals(command)) {
            listenerControl.start();
        } else {
            log.warn("unknown control command={}", command);
        }
    }
}
