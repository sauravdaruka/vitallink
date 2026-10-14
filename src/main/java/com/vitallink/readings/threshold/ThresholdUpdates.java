package com.vitallink.readings.threshold;

import java.time.Duration;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the local copy of thresholds up to date and invalidates the cache (assumption A14):
 *
 * <ol>
 *   <li>replace the patient's rows in PostgreSQL, in one transaction;
 *   <li>after the commit, delete the cached entry, so the next reading loads the new limits;
 *   <li>a moment later, delete it again: a request that read the old limits just before the commit may have written
 *       them back into the cache just after the first delete.
 * </ol>
 *
 * If Redis is down, the delete throws and the whole update is retried; replacing the rows again is harmless.
 */
@Component
class ThresholdUpdates {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ThresholdCache cache;
    private final JsonMapper json;
    private final Duration settleDelay;

    ThresholdUpdates(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ThresholdCache cache,
            JsonMapper json,
            @Value("${thresholds.cache.settle-delay}") Duration settleDelay) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.cache = cache;
        this.json = json;
        this.settleDelay = settleDelay;
    }

    @RabbitListener(queues = ThresholdUpdatesQueueConfig.QUEUE)
    void onThresholdsChanged(Message message) throws InterruptedException {
        ThresholdsChanged update;
        try {
            update = json.readValue(message.getBody(), ThresholdsChanged.class);
        } catch (JacksonException e) {
            throw new AmqpRejectAndDontRequeueException("Unreadable ThresholdsChanged event", e);
        }
        transactions.executeWithoutResult(_ -> {
            jdbc.update("DELETE FROM thresholds WHERE patient_id = ?", update.patientId());
            for (Threshold threshold : update.thresholds()) {
                jdbc.update(
                        "INSERT INTO thresholds (patient_id, metric, below, above) VALUES (?, ?, ?, ?)",
                        update.patientId(),
                        threshold.metric().name(),
                        threshold.below(),
                        threshold.above());
            }
        });
        cache.evict(update.patientId());
        Thread.sleep(settleDelay);
        cache.evict(update.patientId());
    }
}
