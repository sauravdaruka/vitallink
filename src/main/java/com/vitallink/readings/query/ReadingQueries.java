package com.vitallink.readings.query;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads a patient's readings. Every query has a lower time bound, so PostgreSQL skips the older daily partitions,
 * and uses the {@code (patient_id, measured_at)} index inside the rest.
 */
@Repository
class ReadingQueries {

    private static final RowMapper<ReadingView> TO_VIEW = (row, _) -> new ReadingView(
            row.getObject("reading_id", UUID.class),
            row.getString("device_id"),
            row.getString("metric_type"),
            row.getObject("systolic", Integer.class),
            row.getObject("diastolic", Integer.class),
            row.getObject("pulse", Integer.class),
            row.getTimestamp("measured_at").toInstant(),
            row.getTimestamp("received_at").toInstant());

    private final JdbcTemplate jdbc;

    ReadingQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Readings measured since the given time, newest first. */
    List<ReadingView> since(String patientId, Instant from, int limit) {
        return jdbc.query("""
                SELECT device_id, reading_id, metric_type, systolic, diastolic, pulse, measured_at, received_at
                FROM readings
                WHERE patient_id = ? AND measured_at >= ?
                ORDER BY measured_at DESC
                LIMIT ?
                """, TO_VIEW, patientId, Timestamp.from(from), limit);
    }

    /** Readings measured in [from, to), newest first. */
    List<ReadingView> between(String patientId, Instant from, Instant to, int limit) {
        return jdbc.query("""
                SELECT device_id, reading_id, metric_type, systolic, diastolic, pulse, measured_at, received_at
                FROM readings
                WHERE patient_id = ? AND measured_at >= ? AND measured_at < ?
                ORDER BY measured_at DESC
                LIMIT ?
                """, TO_VIEW, patientId, Timestamp.from(from), Timestamp.from(to), limit);
    }
}
