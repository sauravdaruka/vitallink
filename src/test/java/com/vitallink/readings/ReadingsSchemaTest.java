package com.vitallink.readings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Checks the rules the readings table itself enforces, on a real PostgreSQL. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ReadingsSchemaTest {

    private static final String INSERT = """
            INSERT INTO readings (device_id, reading_id, patient_id, metric_type, systolic, diastolic, pulse, measured_at)
            VALUES (?, ?, 'patient-1', 'BLOOD_PRESSURE', ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    // Far outside the window the app keeps partitions for, so these readings always go to the default partition.
    private final Timestamp measuredAt = Timestamp.from(Instant.parse("2000-01-01T07:02:00Z"));

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void retryOfTheSameReadingIsIgnored() {
        UUID readingId = UUID.randomUUID();

        assertThat(insert("bp-001", readingId, 180, 110)).isEqualTo(1);
        assertThat(insert("bp-001", readingId, 180, 110)).isZero();
    }

    @Test
    void sameReadingIdFromAnotherDeviceIsNotADuplicate() {
        UUID readingId = UUID.randomUUID();

        assertThat(insert("bp-001", readingId, 120, 80)).isEqualTo(1);
        assertThat(insert("bp-002", readingId, 120, 80)).isEqualTo(1);
    }

    @Test
    void systolicLowerThanDiastolicIsRefused() {
        assertThatThrownBy(() -> insert("bp-001", UUID.randomUUID(), 80, 120))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("readings_bp_values_check");
    }

    @Test
    void extremeButRealValuesAreStored() {
        assertThat(insert("bp-001", UUID.randomUUID(), 250, 150)).isEqualTo(1);
    }

    @Test
    void readingForADayWithoutAPartitionLandsInTheDefaultPartition() {
        UUID readingId = UUID.randomUUID();
        insert("bp-001", readingId, 120, 80);

        String partition = jdbc.queryForObject(
                "SELECT tableoid::regclass::text FROM readings WHERE reading_id = ?", String.class, readingId);

        assertThat(partition).isEqualTo("readings_default");
    }

    private int insert(String deviceId, UUID readingId, int systolic, int diastolic) {
        return jdbc.update(INSERT, deviceId, readingId, systolic, diastolic, 72, measuredAt);
    }
}
