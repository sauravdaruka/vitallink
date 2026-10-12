package com.vitallink.readings.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends saved outbox events to the broker, oldest first, and marks them sent only after the broker confirms it has
 * them. If the broker is down, events stay in the outbox and are sent on a later run, so none are lost.
 *
 * <p>An event can be sent twice (sent, then a crash before it is marked), so consumers must handle duplicates.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record PendingEvent(long id, UUID eventId, String eventType, String payload) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RabbitTemplate rabbit;
    private final int batchSize;
    private final long confirmTimeoutMillis;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();
    // Logged once when sending starts failing and once when it recovers, not every 200 ms during an outage.
    private final AtomicBoolean failing = new AtomicBoolean();

    OutboxRelay(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            RabbitTemplate rabbit,
            MeterRegistry meters,
            @Value("${outbox.relay.batch-size}") int batchSize,
            @Value("${outbox.relay.confirm-timeout-millis}") long confirmTimeoutMillis) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.rabbit = rabbit;
        this.batchSize = batchSize;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
        Gauge.builder("outbox.pending", pending, AtomicLong::get)
                .description("Events not yet sent to the broker. Should stay near 0.")
                .register(meters);
        Gauge.builder("outbox.oldest.pending.seconds", oldestPendingSeconds, AtomicLong::get)
                .description("Age of the oldest unsent event: how far behind alerting is.")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${outbox.relay.interval}")
    void sendPending() {
        try {
            int sent;
            do {
                sent = transactions.execute(transaction -> sendBatch());
            } while (sent == batchSize);
            if (failing.compareAndSet(true, false)) {
                log.info("Sending outbox events works again");
            }
        } catch (RuntimeException e) {
            if (failing.compareAndSet(false, true)) {
                log.warn("Could not send outbox events; they stay in the outbox and will be retried", e);
            }
        }
        updateMetrics();
    }

    /**
     * FOR UPDATE SKIP LOCKED lets several app instances run the relay at once: each takes different events, and none
     * waits for another. The rows stay locked until they are marked sent.
     */
    private int sendBatch() {
        List<PendingEvent> events = jdbc.query(
                """
                SELECT id, event_id, event_type, payload::text AS payload
                FROM outbox
                WHERE sent_at IS NULL
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (row, i) -> new PendingEvent(
                        row.getLong("id"),
                        row.getObject("event_id", UUID.class),
                        row.getString("event_type"),
                        row.getString("payload")),
                batchSize);
        if (events.isEmpty()) {
            return 0;
        }
        rabbit.invoke(operations -> {
            events.forEach(event -> operations.send(OutboxBrokerConfig.EXCHANGE, event.eventType(), toMessage(event)));
            // Throws if the broker doesn't confirm in time; the transaction then rolls back and nothing is marked.
            operations.waitForConfirmsOrDie(confirmTimeoutMillis);
            return null;
        });
        Long[] ids = events.stream().map(PendingEvent::id).toArray(Long[]::new);
        jdbc.execute((ConnectionCallback<Integer>) connection -> {
            Array idArray = connection.createArrayOf("bigint", ids);
            try (var statement = connection.prepareStatement("UPDATE outbox SET sent_at = now() WHERE id = ANY (?)")) {
                statement.setArray(1, idArray);
                return statement.executeUpdate();
            }
        });
        return events.size();
    }

    private static Message toMessage(PendingEvent event) {
        return MessageBuilder.withBody(event.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(event.eventId().toString())
                .setType(event.eventType())
                // Written to disk by the broker, so a broker restart doesn't lose it.
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }

    /** Sent events are kept for a week, to investigate problems, then removed so the table stays small. */
    @Scheduled(cron = "0 30 * * * *", zone = "UTC")
    void removeOldSentEvents() {
        try {
            int removed = jdbc.update("DELETE FROM outbox WHERE sent_at < now() - interval '7 days'");
            log.debug("Removed {} old outbox events", removed);
        } catch (RuntimeException e) {
            log.warn("Could not remove old outbox events", e);
        }
    }

    private void updateMetrics() {
        try {
            jdbc.query("""
                    SELECT count(*) AS pending,
                           coalesce(extract(epoch FROM now() - min(created_at)), 0)::bigint AS oldest
                    FROM outbox
                    WHERE sent_at IS NULL
                    """, row -> {
                pending.set(row.getLong("pending"));
                oldestPendingSeconds.set(row.getLong("oldest"));
            });
        } catch (RuntimeException e) {
            log.warn("Could not update outbox metrics", e);
        }
    }
}
