package com.vitallink.readings.ingest;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Creates the daily partitions of the readings table, from {@code days-back} days ago to {@code days-ahead}
 * days ahead. Days are UTC days.
 *
 * <p>Runs at startup and every hour. A failure is logged and never stops the app: partitions already exist
 * well ahead, and the default partition catches anything else. Two metrics make a failure visible.
 */
@Component
class ReadingPartitionMaintainer {

    private static final Logger log = LoggerFactory.getLogger(ReadingPartitionMaintainer.class);

    // Any fixed number works; every app instance must use the same one.
    private static final long LOCK_KEY = 4_242_001L;

    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy_MM_dd");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int daysBack;
    private final int daysAhead;
    private final AtomicInteger readyDaysAhead = new AtomicInteger();
    private final AtomicInteger defaultPartitionHasRows = new AtomicInteger();

    ReadingPartitionMaintainer(
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${readings.partitions.days-back}") int daysBack,
            @Value("${readings.partitions.days-ahead}") int daysAhead,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.daysBack = daysBack;
        this.daysAhead = daysAhead;
        Gauge.builder("readings.partitions.ready.days.ahead", readyDaysAhead, AtomicInteger::get)
                .description("Days ahead that already have a partition. Alert when this is below 7.")
                .register(meters);
        Gauge.builder("readings.partitions.default.has.rows", defaultPartitionHasRows, AtomicInteger::get)
                .description("1 if the default partition holds any rows. It should always be 0.")
                .register(meters);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    void createPartitions() {
        try {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                createWhileHoldingLock(connection);
                return null;
            });
        } catch (RuntimeException e) {
            log.error("Could not create readings partitions. New readings still go to the default partition.", e);
        }
        updateMetrics();
    }

    /** Only one app instance creates partitions at a time; the others skip this run. */
    private void createWhileHoldingLock(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (!tryLock(statement)) {
                log.debug("Another instance is creating readings partitions; skipping this run");
                return;
            }
            try {
                LocalDate today = LocalDate.now(clock);
                for (LocalDate day = today.minusDays(daysBack);
                        !day.isAfter(today.plusDays(daysAhead));
                        day = day.plusDays(1)) {
                    createPartition(statement, day);
                }
            } finally {
                statement.execute("SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
            }
        }
    }

    private static boolean tryLock(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT pg_try_advisory_lock(" + LOCK_KEY + ")")) {
            result.next();
            return result.getBoolean(1);
        }
    }

    /** One failed day must not stop the others, so each day is created on its own. */
    private static void createPartition(Statement statement, LocalDate day) {
        // Built only from a LocalDate, never from user input, so it is safe to put in the SQL text.
        String sql =
                "CREATE TABLE IF NOT EXISTS %s PARTITION OF readings FOR VALUES FROM ('%sT00:00:00Z') TO ('%sT00:00:00Z')"
                        .formatted(partitionName(day), day, day.plusDays(1));
        try {
            statement.execute(sql);
        } catch (SQLException e) {
            // Usually means the default partition already holds readings for this day.
            log.error("Could not create readings partition for {}: {}", day, e.getMessage());
        }
    }

    private void updateMetrics() {
        try {
            Set<String> existing = new HashSet<>(jdbc.queryForList(
                    "SELECT inhrelid::regclass::text FROM pg_inherits WHERE inhparent = 'readings'::regclass",
                    String.class));
            LocalDate today = LocalDate.now(clock);
            int ready = 0;
            while (ready < daysAhead && existing.contains(partitionName(today.plusDays(ready + 1)))) {
                ready++;
            }
            readyDaysAhead.set(ready);
            Boolean hasRows = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM readings_default)", Boolean.class);
            defaultPartitionHasRows.set(Boolean.TRUE.equals(hasRows) ? 1 : 0);
        } catch (RuntimeException e) {
            log.warn("Could not update readings partition metrics", e);
        }
    }

    static String partitionName(LocalDate day) {
        return "readings_" + day.format(NAME_FORMAT);
    }
}
