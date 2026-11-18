package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.nocook.service.config.WorkerProperties;
import com.nocook.service.notifications.CardPublisher.CardPublishException;
import com.nocook.service.notifications.MqttCardPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * TESTING.md section 2: the real HiveMQ client against a real Mosquitto running the repo's infra/mosquitto.conf.
 * Plain JUnit (no Spring context): the publisher is constructed with the same collaborators MqttConfig uses.
 */
class MqttPublishIT {

    private static final String CONFIG_PATH = "../../infra/mosquitto/mosquitto.conf";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> MOSQUITTO = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
            .withCopyFileToContainer(MountableFile.forHostPath(CONFIG_PATH), "/mosquitto/config/mosquitto.conf")
            .withExposedPorts(1883)
            .waitingFor(Wait.forListeningPorts(1883));

    private static Mqtt5AsyncClient publisherClient;
    private static Mqtt5AsyncClient subscriberClient;

    @BeforeAll
    static void start() throws Exception {
        MOSQUITTO.start();
        publisherClient = client("it-publisher");
        subscriberClient = client("it-subscriber");
        publisherClient.connectWith().cleanStart(true).send().get(10, TimeUnit.SECONDS);
        subscriberClient.connectWith().cleanStart(true).send().get(10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void stop() {
        if (publisherClient != null) {
            publisherClient.disconnect();
        }
        if (subscriberClient != null) {
            subscriberClient.disconnect();
        }
        MOSQUITTO.stop();
    }

    private static Mqtt5AsyncClient client(String id) {
        return MqttClient.builder().useMqttVersion5().identifier(id)
                .serverHost(MOSQUITTO.getHost()).serverPort(MOSQUITTO.getMappedPort(1883)).buildAsync();
    }

    @Test
    void card_and_call_payloads_arrive_on_their_outlet_topics_at_qos1() throws Exception {
        List<String> topics = new CopyOnWriteArrayList<>();
        List<String> payloads = new CopyOnWriteArrayList<>();
        subscriberClient.subscribeWith().topicFilter("nocook/outlets/outlet-mq/#").qos(MqttQos.AT_LEAST_ONCE)
                .callback(publish -> {
                    topics.add(publish.getTopic().toString());
                    payloads.add(new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8));
                }).send().get(10, TimeUnit.SECONDS);
        MqttCardPublisher publisher = new MqttCardPublisher(publisherClient,
                new WorkerProperties.Mqtt(true, MOSQUITTO.getHost(), MOSQUITTO.getMappedPort(1883), Duration.ofSeconds(2)));

        publisher.publishCard("outlet-mq", "{\"messageId\":\"m-1\"}");
        publisher.publishCall("outlet-mq", "{\"messageId\":\"m-2\"}");

        await().atMost(5, TimeUnit.SECONDS).until(() -> payloads.size() == 2);
        ItSummary.print("MQTT", Map.of("received", payloads.size()), payloads.size() == 2);
        assertThat(topics).containsExactlyInAnyOrder("nocook/outlets/outlet-mq/cards", "nocook/outlets/outlet-mq/calls");
        assertThat(payloads).containsExactlyInAnyOrder("{\"messageId\":\"m-1\"}", "{\"messageId\":\"m-2\"}");
    }

    @Test
    void publishing_to_an_unreachable_broker_fails_with_card_publish_exception() {
        Mqtt5AsyncClient lonely = MqttClient.builder().useMqttVersion5().identifier("it-lonely")
                .serverHost("127.0.0.1").serverPort(1).buildAsync();
        MqttCardPublisher publisher = new MqttCardPublisher(lonely,
                new WorkerProperties.Mqtt(true, "127.0.0.1", 1, Duration.ofMillis(500)));

        assertThatThrownBy(() -> publisher.publishCard("outlet-mq", "{}")).isInstanceOf(CardPublishException.class);
    }
}
