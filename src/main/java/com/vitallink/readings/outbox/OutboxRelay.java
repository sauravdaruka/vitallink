package com.vitallink.readings.outbox;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Duration sentRetention;
    // Logged once when sending starts failing and once when it recovers, not every 200 ms during an outage.
    private final AtomicBoolean failing = new AtomicBoolean();

    OutboxRelay(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            RabbitTemplate rabbit,
            @Value("${outbox.relay.batch-size}") int batchSize,
            @Value("${outbox.relay.confirm-timeout-millis}") long confirmTimeoutMillis,
            @Value("${outbox.cleanup.retention}") Duration sentRetention) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.rabbit = rabbit;
        this.batchSize = batchSize;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
        this.sentRetention = sentRetention;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.interval}")
    void sendPending() {
        try {
            int sent;
            do {
                sent = transactions.execute(_ -> sendBatch());
            } while (sent == batchSize);
            if (failing.compareAndSet(true, false)) {
                log.info("Sending outbox events works again");
            }
        } catch (RuntimeException e) {
            if (failing.compareAndSet(false, true)) {
                log.warn("Could not send outbox events; they stay in the outbox and will be retried", e);
            }
        }
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
                (row, _) -> new PendingEvent(
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

    /** Sent events are kept for a while, to investigate problems, then removed so the table stays small. */
    @Scheduled(cron = "${outbox.cleanup.cron}", zone = "UTC")
    void removeOldSentEvents() {
        try {
            int removed = jdbc.update(
                    "DELETE FROM outbox WHERE sent_at < now() - make_interval(secs => ?)", sentRetention.toSeconds());
            log.debug("Removed {} old outbox events", removed);
        } catch (RuntimeException e) {
            log.warn("Could not remove old outbox events", e);
        }
    }
}
