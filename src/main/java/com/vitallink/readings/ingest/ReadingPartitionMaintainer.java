package com.vitallink.readings.ingest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
 * well ahead, and the default partition catches anything else.
 */
@Component
class ReadingPartitionMaintainer {

    private static final Logger log = LoggerFactory.getLogger(ReadingPartitionMaintainer.class);

    // Any fixed number works; every app instance must use the same one, so it is a constant, not config.
    private static final long LOCK_KEY = 4_242_001L;

    // Session-level advisory lock: held by this connection until unlocked, whatever transaction it is in.
    private static final String TRY_LOCK = "SELECT pg_try_advisory_lock(?)";
    private static final String UNLOCK = "SELECT pg_advisory_unlock(?)";

    // DDL can't take bind parameters, so the name and bounds are put in the text. They are built only from a
    // LocalDate, never from user input, so this is safe.
    private static final String CREATE_PARTITION =
            "CREATE TABLE IF NOT EXISTS %s PARTITION OF readings FOR VALUES FROM ('%sT00:00:00Z') TO ('%sT00:00:00Z')";

    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy_MM_dd");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int daysBack;
    private final int daysAhead;

    ReadingPartitionMaintainer(
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${readings.partitions.days-back}") int daysBack,
            @Value("${readings.partitions.days-ahead}") int daysAhead) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.daysBack = daysBack;
        this.daysAhead = daysAhead;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "${readings.partitions.cron}", zone = "UTC")
    void createPartitions() {
        try {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                createWhileHoldingLock(connection);
                return null;
            });
        } catch (RuntimeException e) {
            log.error("Could not create readings partitions. New readings still go to the default partition.", e);
        }
    }

    /** Only one app instance creates partitions at a time; the others skip this run. */
    private void createWhileHoldingLock(Connection connection) throws SQLException {
        if (!callLockFunction(connection, TRY_LOCK)) {
            log.debug("Another instance is creating readings partitions; skipping this run");
            return;
        }
        try (Statement statement = connection.createStatement()) {
            LocalDate today = LocalDate.now(clock);
            for (LocalDate day = today.minusDays(daysBack);
                    !day.isAfter(today.plusDays(daysAhead));
                    day = day.plusDays(1)) {
                createPartition(statement, day);
            }
        } finally {
            callLockFunction(connection, UNLOCK);
        }
    }

    /** Runs TRY_LOCK or UNLOCK with LOCK_KEY and returns the function's true/false result. */
    private static boolean callLockFunction(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    /** One failed day must not stop the others, so each day is created on its own. */
    private static void createPartition(Statement statement, LocalDate day) {
        String sql = CREATE_PARTITION.formatted(partitionName(day), day, day.plusDays(1));
        try {
            statement.execute(sql);
        } catch (SQLException e) {
            // Usually means the default partition already holds readings for this day.
            log.error("Could not create readings partition for {}: {}", day, e.getMessage());
        }
    }

    static String partitionName(LocalDate day) {
        return "readings_" + day.format(NAME_FORMAT);
    }
}
