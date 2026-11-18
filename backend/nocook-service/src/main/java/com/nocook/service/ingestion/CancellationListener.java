package com.nocook.service.ingestion;

import com.nocook.domain.model.CancellationEvent;
import com.nocook.service.config.RabbitTopologyConfig;
import com.nocook.service.config.WorkerProperties;
import com.nocook.service.observability.NocookMeters;
import com.nocook.service.oms.OmsUnavailableException;
import java.time.Duration;
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
    private final WorkerProperties.OmsRetry omsRetry;

    public CancellationListener(
            EventValidator validator,
            CancellationIngestor ingestor,
            NocookMeters meters,
            WorkerProperties.OmsRetry omsRetry) {
        this.omsRetry = omsRetry;
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
        ingestWithBackoff(event, publishedAt(message));
    }

    /**
     * Listener-level backoff while OMS is unreachable. Throwing straight away would requeue instantly and could burn the
     * quorum queue's 10-delivery limit within milliseconds, sending real cancellations to the DLQ. Instead this thread
     * waits (0.5 s doubling to 2 s, about {@code maxTotal} in all, default 70 s so a ~60 s OMS outage is ridden out)
     * and retries; only when the budget is spent does it throw, which requeues once. The budget is counted in sleeps,
     * not clock reads, so it is independent of the injected Clock.
     */
    private void ingestWithBackoff(CancellationEvent event, @Nullable Instant publishedAt) {
        Duration slept = Duration.ZERO;
        Duration pause = omsRetry.initial();
        Instant lagInstant = publishedAt;
        while (true) {
            try {
                ingestor.ingest(event, lagInstant);
                return;
            } catch (OmsUnavailableException ex) {
                if (slept.compareTo(omsRetry.maxTotal()) >= 0) {
                    throw ex;
                }
                log.warn("oms unavailable, backing off orderId={} pauseMs={} sleptMs={}",
                        event.orderId(), pause.toMillis(), slept.toMillis());
                sleep(pause);
                slept = slept.plus(pause);
                pause = pause.multipliedBy(2).compareTo(omsRetry.max()) > 0 ? omsRetry.max() : pause.multipliedBy(2);
                lagInstant = null; // consume.lag is recorded once, on the first attempt
            }
        }
    }

    private static void sleep(Duration pause) {
        try {
            Thread.sleep(pause.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off", interrupted);
        }
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
