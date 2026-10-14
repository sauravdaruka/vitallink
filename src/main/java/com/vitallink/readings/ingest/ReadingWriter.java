package com.vitallink.readings.ingest;

import com.vitallink.readings.device.AuthenticatedDevice;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ReadingWriter {

    // The whole batch is one statement and one round trip to the database.
    // ON CONFLICT DO NOTHING lets the primary key decide what is a duplicate, so two retries arriving at the
    // same moment can't both be stored. RETURNING tells us which readings were actually new.
    private static final String INSERT = """
            INSERT INTO readings (device_id, reading_id, patient_id, metric_type, systolic, diastolic, pulse, measured_at)
            SELECT ?, r.reading_id::uuid, ?, r.metric_type,
                   r.systolic::smallint, r.diastolic::smallint, r.pulse::smallint, r.measured_at::timestamptz
            FROM unnest(?::text[], ?::text[], ?::int[], ?::int[], ?::int[], ?::text[])
                AS r(reading_id, metric_type, systolic, diastolic, pulse, measured_at)
            ON CONFLICT DO NOTHING
            RETURNING reading_id
            """;

    private final JdbcTemplate jdbc;

    ReadingWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the readings that aren't stored yet, and returns their IDs. The patient comes from the device's
     * key, never from the request.
     */
    Set<UUID> insertNew(AuthenticatedDevice device, List<ReadingInput> readings) {
        List<UUID> inserted = jdbc.query(
                connection -> {
                    PreparedStatement statement = connection.prepareStatement(INSERT);
                    statement.setString(1, device.deviceId());
                    statement.setString(2, device.patientId());
                    statement.setArray(
                            3,
                            connection.createArrayOf(
                                    "text", column(readings, r -> r.readingId().toString())));
                    statement.setArray(4, connection.createArrayOf("text", column(readings, ReadingInput::metricType)));
                    statement.setArray(5, connection.createArrayOf("int4", column(readings, ReadingInput::systolic)));
                    statement.setArray(6, connection.createArrayOf("int4", column(readings, ReadingInput::diastolic)));
                    statement.setArray(7, connection.createArrayOf("int4", column(readings, ReadingInput::pulse)));
                    statement.setArray(
                            8,
                            connection.createArrayOf(
                                    "text", column(readings, r -> r.measuredAt().toString())));
                    return statement;
                },
                (row, _) -> row.getObject("reading_id", UUID.class));
        return new HashSet<>(inserted);
    }

    private static Object[] column(List<ReadingInput> readings, Function<ReadingInput, ?> field) {
        return readings.stream().map(field).toArray();
    }
}
