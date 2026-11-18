package com.nocook.service.it;

import com.nocook.service.notifications.CardPublisher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Captures MQTT payloads instead of talking to a broker. {@link #failNext} simulates a broker outage. */
public class RecordingCardPublisher implements CardPublisher {

    public record Published(String channel, String outletId, String json) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<Published> published = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresRemaining = new AtomicInteger();

    public void failNext(int count) {
        failuresRemaining.set(count);
    }

    public void reset() {
        published.clear();
        failuresRemaining.set(0);
    }

    public List<Published> all() {
        return List.copyOf(published);
    }

    public List<Published> cards() {
        return published.stream().filter(p -> p.channel().equals("cards")).toList();
    }

    public List<Published> calls() {
        return published.stream().filter(p -> p.channel().equals("calls")).toList();
    }

    public List<String> messageIds() {
        return published.stream().map(p -> field(p, "messageId")).toList();
    }

    public static String field(Published message, String name) {
        JsonNode node = JSON.readTree(message.json()).get(name);
        return node == null || node.isNull() ? null : node.asString();
    }

    @Override
    public void publishCard(String outletId, String cardJson) {
        record("cards", outletId, cardJson);
    }

    @Override
    public void publishCall(String outletId, String callJson) {
        record("calls", outletId, callJson);
    }

    private void record(String channel, String outletId, String json) {
        if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new CardPublishException("simulated broker failure", null);
        }
        published.add(new Published(channel, outletId, json));
    }
}
