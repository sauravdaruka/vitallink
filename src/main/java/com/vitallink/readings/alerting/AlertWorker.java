package com.vitallink.readings.alerting;

import com.vitallink.readings.ingest.ReadingBreached;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Takes alert jobs from the queue. If handling fails, Spring retries it a few times with growing delays; after the
 * last retry the job goes to the dead-letter queue.
 */
@Component
class AlertWorker {

    private final AlertService alerts;
    private final JsonMapper json;

    AlertWorker(AlertService alerts, JsonMapper json) {
        this.alerts = alerts;
        this.json = json;
    }

    @RabbitListener(queues = AlertQueueConfig.QUEUE)
    void onReadingBreached(Message message) {
        ReadingBreached event;
        try {
            event = json.readValue(message.getBody(), ReadingBreached.class);
        } catch (JacksonException e) {
            // A message we can't read will never succeed, so skip the retries and send it to the dead-letter queue.
            throw new AmqpRejectAndDontRequeueException("Unreadable ReadingBreached event", e);
        }
        alerts.handle(event);
    }
}
