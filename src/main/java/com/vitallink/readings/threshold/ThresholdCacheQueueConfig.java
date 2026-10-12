package com.vitallink.readings.threshold;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The threshold cache owns its queue and subscribes it to threshold changes. */
@Configuration(proxyBeanMethods = false)
class ThresholdCacheQueueConfig {

    static final String QUEUE = "thresholds.cache-invalidation";

    /** Where a delete goes after failing every retry. Someone must look at it, or the stale entry lives until its TTL. */
    static final String DEAD_LETTER_QUEUE = QUEUE + ".dead";

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
    Binding thresholdsChangedBinding(Queue thresholdsChangedQueue, TopicExchange readingsEvents) {
        return BindingBuilder.bind(thresholdsChangedQueue).to(readingsEvents).with(ThresholdsChanged.TYPE);
    }
}
