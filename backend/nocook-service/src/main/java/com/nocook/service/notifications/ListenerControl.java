package com.nocook.service.notifications;

import com.nocook.service.ingestion.CancellationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/**
 * Stops and starts this replica's {@code cancellations} listener container (chaos action "listener"). Idempotent:
 * stopping a stopped container or starting a running one is a no-op.
 */
@Component
public class ListenerControl {

    public static final String RUNNING = "RUNNING";
    public static final String STOPPED = "STOPPED";

    private static final Logger log = LoggerFactory.getLogger(ListenerControl.class);

    private final RabbitListenerEndpointRegistry registry;

    public ListenerControl(RabbitListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    public synchronized void stop() {
        MessageListenerContainer container = container();
        if (container != null && container.isRunning()) {
            container.stop();
            log.info("listener stopped id={}", CancellationListener.LISTENER_ID);
        }
    }

    public synchronized void start() {
        MessageListenerContainer container = container();
        if (container != null && !container.isRunning()) {
            container.start();
            log.info("listener started id={}", CancellationListener.LISTENER_ID);
        }
    }

    public boolean isRunning() {
        MessageListenerContainer container = container();
        return container != null && container.isRunning();
    }

    public String state() {
        return isRunning() ? RUNNING : STOPPED;
    }

    /** Applies a desired state ("RUNNING" or "STOPPED"). */
    public void apply(String desired) {
        if (STOPPED.equals(desired)) {
            stop();
        } else {
            start();
        }
    }

    private MessageListenerContainer container() {
        return registry.getListenerContainer(CancellationListener.LISTENER_ID);
    }
}
