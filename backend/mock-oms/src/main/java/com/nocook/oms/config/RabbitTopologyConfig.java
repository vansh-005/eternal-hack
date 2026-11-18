package com.nocook.oms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API.md section 3 topology. Both apps declare it; declarations are idempotent. */
@Configuration
public class RabbitTopologyConfig {

    public static final String EVENTS_EXCHANGE = "order.events";
    public static final String DLX_EXCHANGE = "order.events.dlx";
    public static final String CANCELLATIONS_QUEUE = "nocook.cancellations";
    public static final String CANCELLATIONS_DLQ = "nocook.cancellations.dlq";
    public static final String CANCELLED_ROUTING_KEY = "order.cancelled";

    private static final Logger log = LoggerFactory.getLogger(RabbitTopologyConfig.class);

    @Bean
    public Declarables orderEventsTopology() {
        TopicExchange eventsExchange = new TopicExchange(EVENTS_EXCHANGE, true, false);
        FanoutExchange deadLetterExchange = new FanoutExchange(DLX_EXCHANGE, true, false);
        Queue cancellations = QueueBuilder.durable(CANCELLATIONS_QUEUE)
                .quorum()
                .deliveryLimit(10)
                .deadLetterExchange(DLX_EXCHANGE)
                .build();
        Queue deadLetters = QueueBuilder.durable(CANCELLATIONS_DLQ).quorum().build();
        Binding cancellationsBinding =
                BindingBuilder.bind(cancellations).to(eventsExchange).with(CANCELLED_ROUTING_KEY);
        Binding deadLetterBinding = BindingBuilder.bind(deadLetters).to(deadLetterExchange);
        return new Declarables(
                eventsExchange, deadLetterExchange, cancellations, deadLetters, cancellationsBinding, deadLetterBinding);
    }

    /** Opens one connection at startup so the RabbitAdmin declares the topology before the first publish. */
    @Bean
    public ApplicationRunner declareTopologyOnStartup(ConnectionFactory connectionFactory) {
        return args -> {
            try {
                connectionFactory.createConnection().close();
            } catch (RuntimeException ex) {
                log.warn("topology declaration deferred, broker not reachable reason={}", ex.getMessage());
            }
        };
    }
}
