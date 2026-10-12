package com.vitallink.readings.alerting;

import com.vitallink.readings.ingest.ReadingBreached;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Alerting owns its queue and subscribes it to the events it needs. Quorum queues keep messages on several broker
 * nodes, so losing one node doesn't lose alert jobs.
 */
@Configuration(proxyBeanMethods = false)
class AlertQueueConfig {

    static final String QUEUE = "alerting.reading-breached";

    /** Where a job goes after failing every retry, so it can't block the others. Someone must look at it. */
    static final String DEAD_LETTER_QUEUE = QUEUE + ".dead";

    @Bean
    Queue readingBreachedQueue() {
        return QueueBuilder.durable(QUEUE)
                .quorum()
                .deadLetterExchange("")
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    Queue readingBreachedDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).quorum().build();
    }

    @Bean
    Binding readingBreachedBinding(Queue readingBreachedQueue, TopicExchange readingsEvents) {
        return BindingBuilder.bind(readingBreachedQueue).to(readingsEvents).with(ReadingBreached.TYPE);
    }
}
