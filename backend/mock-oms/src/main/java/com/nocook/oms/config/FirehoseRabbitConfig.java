package com.nocook.oms.config;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dedicated broker connection for the firehose: observation OFF, publisher confirms OFF, enough cached channels
 * for every publisher thread. Exposed through a holder bean on purpose: registering a second RabbitTemplate or
 * ConnectionFactory bean would make the auto-configured ones (used by the outbox relay) back off.
 */
@Configuration
public class FirehoseRabbitConfig {

    /** Holder so the firehose template is not a RabbitTemplate bean. */
    public static final class FirehoseRabbit implements DisposableBean {
        private final CachingConnectionFactory connectionFactory;
        private final RabbitTemplate template;

        FirehoseRabbit(CachingConnectionFactory connectionFactory, RabbitTemplate template) {
            this.connectionFactory = connectionFactory;
            this.template = template;
        }

        public RabbitTemplate template() {
            return template;
        }

        @Override
        public void destroy() {
            connectionFactory.destroy();
        }
    }

    @Bean
    public FirehoseRabbit firehoseRabbit(
            @Value("${spring.rabbitmq.host:rabbitmq}") String host,
            @Value("${spring.rabbitmq.port:5672}") int port,
            @Value("${spring.rabbitmq.username:nocook}") String username,
            @Value("${spring.rabbitmq.password:nocook}") String password,
            @Value("${oms.firehose.channel-cache:64}") int channelCache) {
        CachingConnectionFactory factory = new CachingConnectionFactory(host, port);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setConnectionNameStrategy(cf -> "mock-oms-firehose");
        factory.setChannelCacheSize(channelCache);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.NONE);
        RabbitTemplate template = new RabbitTemplate(factory);
        template.setObservationEnabled(false);
        return new FirehoseRabbit(factory, template);
    }
}
