package com.nocook.service.ingestion;

import com.nocook.domain.model.CancellationEvent;
import com.nocook.service.config.RabbitTopologyConfig;
import com.nocook.service.observability.NocookMeters;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code nocook.cancellations}. Takes the raw {@link Message} and parses the body with the Jackson 3
 * JsonMapper, so no AMQP message converter is involved. Invalid payloads are rejected without requeue (to the DLQ).
 * Container id "cancellations" is what ListenerControl stops and starts.
 */
@Component
public class CancellationListener {

    public static final String LISTENER_ID = "cancellations";
    public static final String PUBLISHED_AT_HEADER = "x-published-at";

    private static final Logger log = LoggerFactory.getLogger(CancellationListener.class);

    private final EventValidator validator;
    private final CancellationIngestor ingestor;
    private final NocookMeters meters;

    public CancellationListener(EventValidator validator, CancellationIngestor ingestor, NocookMeters meters) {
        this.validator = validator;
        this.ingestor = ingestor;
        this.meters = meters;
    }

    @RabbitListener(id = LISTENER_ID, queues = RabbitTopologyConfig.CANCELLATIONS_QUEUE)
    public void onMessage(Message message) {
        CancellationEvent event;
        try {
            event = validator.validate(message.getBody());
        } catch (EventValidator.InvalidEventException ex) {
            meters.eventConsumed("invalid");
            log.warn("invalid cancellation rejected to DLQ reason={}", ex.getMessage());
            throw new AmqpRejectAndDontRequeueException(ex.getMessage(), ex);
        }
        ingestor.ingest(event, publishedAt(message));
    }

    private static @Nullable Instant publishedAt(Message message) {
        Object header = message.getMessageProperties().getHeader(PUBLISHED_AT_HEADER);
        if (header == null) {
            return null;
        }
        try {
            return Instant.parse(header.toString());
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
