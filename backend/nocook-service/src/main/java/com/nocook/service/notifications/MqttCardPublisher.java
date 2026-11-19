package com.nocook.service.notifications;

import com.hivemq.client.mqtt.MqttClientState;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult;
import com.nocook.service.config.WorkerProperties;
import com.nocook.service.observability.Spans;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QoS 1 publisher: returns only after PUBACK (timeout 2 s, then {@link CardPublishException}). The PUBACK is a transport
 * receipt, never a human ACK (CLAUDE.md rule 5).
 */
public class MqttCardPublisher implements CardPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqttCardPublisher.class);

    private final Mqtt5AsyncClient client;
    private final WorkerProperties.Mqtt properties;

    private final @org.jspecify.annotations.Nullable Spans spans;

    public MqttCardPublisher(Mqtt5AsyncClient client, WorkerProperties.Mqtt properties) {
        this(client, properties, null);
    }

    public MqttCardPublisher(
            Mqtt5AsyncClient client, WorkerProperties.Mqtt properties, @org.jspecify.annotations.Nullable Spans spans) {
        this.client = client;
        this.properties = properties;
        this.spans = spans;
    }

    public static void connect(Mqtt5AsyncClient client) {
        client.connectWith().cleanStart(true).keepAlive(30).send().whenComplete((ack, error) -> {
            if (error != null) {
                log.warn("mqtt connect failed, will retry reason={}", error.toString());
            }
        });
    }

    @Override
    public void publishCard(String outletId, String cardJson) {
        publish("nocook/outlets/" + outletId + "/cards", cardJson);
    }

    @Override
    public void publishCall(String outletId, String callJson) {
        publish("nocook/outlets/" + outletId + "/calls", callJson);
    }

    private void publish(String topic, String json) {
        if (spans == null) {
            publishNow(topic, json);
            return;
        }
        spans.inSpan("mqtt.publish", null, Map.of("topic", topic), () -> publishNow(topic, json));
    }

    private void publishNow(String topic, String json) {
        if (client.getState() == MqttClientState.DISCONNECTED) {
            connect(client);
        }
        CompletableFuture<Mqtt5PublishResult> pending = client.publishWith()
                .topic(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(false)
                .payload(json.getBytes(StandardCharsets.UTF_8))
                .send();
        try {
            Mqtt5PublishResult result = pending.get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (result.getError().isPresent()) {
                throw new CardPublishException("publish failed topic=" + topic, result.getError().get());
            }
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw new CardPublishException("no PUBACK within timeout topic=" + topic, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CardPublishException("interrupted topic=" + topic, ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new CardPublishException("publish failed topic=" + topic, ex.getCause());
        }
    }
}
