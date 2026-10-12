package com.vitallink.readings.outbox;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The exchange that outbox events are published to. Consumers bind their own queues to it. */
@Configuration(proxyBeanMethods = false)
public class OutboxBrokerConfig {

    /** Events are routed by their type, so each consumer subscribes only to the events it needs. */
    public static final String EXCHANGE = "readings.events";

    @Bean
    TopicExchange readingsEvents() {
        return new TopicExchange(EXCHANGE, true, false);
    }
}
