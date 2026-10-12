package com.vitallink.readings.threshold;

import java.time.Clock;
import java.time.Duration;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The guaranteed delete behind every threshold change. If Redis is down, the delete fails and is retried, then goes
 * to the dead-letter queue. Deleting twice is harmless, so a repeated message needs no special handling.
 */
@Component
class ThresholdCacheInvalidator {

    private final ThresholdCache cache;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration settleDelay;

    ThresholdCacheInvalidator(
            ThresholdCache cache,
            JsonMapper json,
            Clock clock,
            @Value("${thresholds.cache.settle-delay}") Duration settleDelay) {
        this.cache = cache;
        this.json = json;
        this.clock = clock;
        this.settleDelay = settleDelay;
    }

    @RabbitListener(queues = ThresholdCacheQueueConfig.QUEUE)
    void onThresholdsChanged(Message message) throws InterruptedException {
        ThresholdsChanged event;
        try {
            event = json.readValue(message.getBody(), ThresholdsChanged.class);
        } catch (JacksonException e) {
            throw new AmqpRejectAndDontRequeueException("Unreadable ThresholdsChanged event", e);
        }
        // A request that read the old limits just before the change was saved can write them into the cache just
        // after the first delete. Deleting once the change is at least settle-delay old removes that copy too.
        Duration wait = Duration.between(clock.instant(), event.changedAt().plus(settleDelay));
        if (wait.isPositive()) {
            Thread.sleep(wait);
        }
        cache.evict(event.patientId());
    }
}
