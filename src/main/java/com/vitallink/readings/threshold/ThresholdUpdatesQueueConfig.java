package com.vitallink.readings.threshold;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Subscribes to threshold changes from the care-plan system. That system owns the {@code careplan.events} exchange;
 * it is declared here too only so the service runs on its own locally.
 */
@Configuration(proxyBeanMethods = false)
class ThresholdUpdatesQueueConfig {

    static final String CAREPLAN_EXCHANGE = "careplan.events";
    static final String QUEUE = "readings.thresholds-changed";

    /** Where an update goes after failing every retry. Someone must look at it. */
    static final String DEAD_LETTER_QUEUE = QUEUE + ".dead";

    @Bean
    TopicExchange careplanEvents() {
        return new TopicExchange(CAREPLAN_EXCHANGE);
    }

    @Bean
    Queue thresholdsChangedQueue() {
        return QueueBuilder.durable(QUEUE)
                .quorum()
                .deadLetterExchange("")
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    Queue thresholdsChangedDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).quorum().build();
    }

    @Bean
    Binding thresholdsChangedBinding(Queue thresholdsChangedQueue, TopicExchange careplanEvents) {
        return BindingBuilder.bind(thresholdsChangedQueue).to(careplanEvents).with(ThresholdsChanged.TYPE);
    }
}
