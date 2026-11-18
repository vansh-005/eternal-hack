package com.nocook.oms.testsupport;

import com.nocook.oms.config.RabbitTopologyConfig;
import com.nocook.oms.outbox.OutboxRelay;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** F6 duplicate storm: re-publishes the last N order.cancelled events, same eventId and payload, x-replay=true. */
@RestController
@RequestMapping("/oms/api/test")
public class ReplayController {

    public record ReplayRequest(@Min(1) @Max(10_000) int lastN, @Min(1) @Max(1_000) int times) {}

    public record ReplayResponse(long republished) {}

    private record StoredEvent(UUID eventId, String payload) {}

    private final JdbcClient jdbc;
    private final RabbitTemplate rabbitTemplate;
    private final JsonMapper jsonMapper;

    public ReplayController(JdbcClient jdbc, RabbitTemplate rabbitTemplate, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.rabbitTemplate = rabbitTemplate;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping("/replay-cancellations")
    public ReplayResponse replay(@Valid @RequestBody ReplayRequest request) {
        List<StoredEvent> events = jdbc.sql("""
                        select event_id, payload::text as payload
                        from oms.outbox
                        where routing_key = 'order.cancelled'
                        order by created_at desc, id desc
                        limit :n""")
                .param("n", request.lastN())
                .query((rs, row) -> new StoredEvent(rs.getObject("event_id", UUID.class), rs.getString("payload")))
                .list();
        List<Message> messages = events.stream().map(this::toMessage).toList();
        Long republished = rabbitTemplate.invoke(ops -> {
            long count = 0;
            for (int round = 0; round < request.times(); round++) {
                for (Message message : messages) {
                    ops.send(RabbitTopologyConfig.EVENTS_EXCHANGE, RabbitTopologyConfig.CANCELLED_ROUTING_KEY, message);
                    count++;
                }
            }
            ops.waitForConfirmsOrDie(30_000);
            return count;
        });
        return new ReplayResponse(republished == null ? 0 : republished);
    }

    private Message toMessage(StoredEvent event) {
        ObjectNode payload = (ObjectNode) jsonMapper.readTree(event.payload());
        payload.remove(OutboxRelay.TRACEPARENT_KEY);
        return MessageBuilder.withBody(jsonMapper.writeValueAsBytes(payload))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(event.eventId().toString())
                .setHeader("x-replay", "true")
                .build();
    }
}
