package com.nocook.oms.outbox;

import com.nocook.oms.config.OmsMeters;
import com.nocook.oms.config.RabbitTopologyConfig;
import com.nocook.oms.config.Timestamps;
import com.nocook.oms.outbox.OutboxRepository.OutboxRow;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Transactional-outbox relay. Claims PENDING rows with SKIP LOCKED, publishes them with publisher confirms,
 * then marks them SENT in the same transaction. A failure rolls back and the rows are retried (duplicates are
 * safe: the consumer dedupes on event id).
 *
 * The outbox table has no traceparent column (DATA_MODEL.md is verbatim), so OrderService stores the request's
 * traceparent inside the payload under the key "traceparent". The relay strips it from the AMQP body and sends it
 * as the "traceparent" message header, and makes the publish span a child of that context so the consumer joins the
 * original request trace.
 */
@Component
public class OutboxRelay {

    public static final String TRACEPARENT_KEY = "traceparent";
    public static final String PUBLISHED_AT_HEADER = "x-published-at";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final OmsMeters meters;
    private final ObjectProvider<Tracer> tracerProvider;
    private final ObjectProvider<Propagator> propagatorProvider;
    private final int batchSize;
    private final long confirmTimeoutMillis;
    private final ReentrantLock runLock = new ReentrantLock();

    public OutboxRelay(
            OutboxRepository outboxRepository,
            RabbitTemplate rabbitTemplate,
            TransactionTemplate transactionTemplate,
            Clock clock,
            JsonMapper jsonMapper,
            OmsMeters meters,
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<Propagator> propagatorProvider,
            @Value("${oms.relay.batch:200}") int batchSize,
            @Value("${oms.relay.confirm-timeout:5000}") long confirmTimeoutMillis) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
        this.meters = meters;
        this.tracerProvider = tracerProvider;
        this.propagatorProvider = propagatorProvider;
        this.batchSize = batchSize;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    /** Asks for an immediate drain on a virtual thread; the scheduled tick is the safety net. */
    public void wake() {
        Thread.startVirtualThread(this::tick);
    }

    @Scheduled(fixedDelayString = "${oms.relay.tick:20ms}")
    public void tick() {
        if (!runLock.tryLock()) {
            return;
        }
        try {
            drain();
        } catch (RuntimeException ex) {
            log.warn("relay tick failed, rows stay PENDING reason={}", ex.toString());
        } finally {
            runLock.unlock();
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void sampleGauge() {
        try {
            meters.outboxPending(outboxRepository.countPending());
        } catch (RuntimeException ex) {
            log.debug("outbox gauge sample failed reason={}", ex.toString());
        }
    }

    private void drain() {
        int publishedInBatch = batchSize;
        while (publishedInBatch >= batchSize) {
            Integer published = transactionTemplate.execute(status -> publishOneBatch());
            publishedInBatch = published == null ? 0 : published;
        }
    }

    private int publishOneBatch() {
        List<OutboxRow> claimedRows = outboxRepository.claimPending(batchSize);
        if (claimedRows.isEmpty()) {
            return 0;
        }
        List<UUID> publishedIds = new ArrayList<>(claimedRows.size());
        rabbitTemplate.invoke(operations -> {
            for (OutboxRow row : claimedRows) {
                sendRow(operations, row);
                publishedIds.add(row.eventId());
            }
            operations.waitForConfirmsOrDie(confirmTimeoutMillis);
            return null;
        });
        Instant confirmedAt = clock.instant();
        outboxRepository.markSent(publishedIds, confirmedAt);
        for (OutboxRow row : claimedRows) {
            meters.relayPublished(Duration.between(row.createdAt(), confirmedAt));
            meters.eventPublished(row.routingKey(), false);
        }
        return claimedRows.size();
    }

    private void sendRow(RabbitOperations operations, OutboxRow row) {
        ObjectNode payload = (ObjectNode) jsonMapper.readTree(row.payloadJson());
        JsonNode traceparentNode = payload.remove(TRACEPARENT_KEY);
        String traceparent = traceparentNode == null || traceparentNode.isNull() ? null : traceparentNode.asString();
        MessageBuilderSupport<Message> builder = MessageBuilder.withBody(jsonMapper.writeValueAsBytes(payload))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(row.eventId().toString())
                .setHeader(PUBLISHED_AT_HEADER, Timestamps.format(clock.instant()));
        if (traceparent != null) {
            builder.setHeader(TRACEPARENT_KEY, traceparent);
        }
        Message message = builder.build();
        sendInsideParentSpan(operations, row.routingKey(), message, traceparent);
    }

    private void sendInsideParentSpan(
            RabbitOperations operations, String routingKey, Message message, @Nullable String traceparent) {
        Tracer tracer = tracerProvider.getIfAvailable();
        Propagator propagator = propagatorProvider.getIfAvailable();
        if (tracer == null || propagator == null || traceparent == null) {
            operations.send(RabbitTopologyConfig.EVENTS_EXCHANGE, routingKey, message);
            return;
        }
        Span publishSpan = propagator
                .extract(Map.of(TRACEPARENT_KEY, traceparent), Map<String, String>::get)
                .name("oms.relay.send")
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(publishSpan)) {
            operations.send(RabbitTopologyConfig.EVENTS_EXCHANGE, routingKey, message);
        } finally {
            publishSpan.end();
        }
    }
}
