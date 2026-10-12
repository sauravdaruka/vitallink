package com.vitallink.readings.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.readings.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Each test uses its own year, so tests don't see each other's partitions. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReadingPartitionMaintainerTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsAPartitionForEveryDayFromDaysBackToDaysAhead() {
        maintainerAt("2031-03-10").createPartitions();

        assertThat(partitionExists("2031-02-08")).isTrue();
        assertThat(partitionExists("2031-04-09")).isTrue();
        assertThat(partitionExists("2031-02-07")).isFalse();
        assertThat(partitionExists("2031-04-10")).isFalse();
    }

    @Test
    void aReadingGoesToThePartitionForItsUtcDay() {
        maintainerAt("2032-03-10").createPartitions();

        UUID readingId = insertReading("2032-03-10T23:59:59Z");

        assertThat(partitionOf(readingId)).isEqualTo("readings_2032_03_10");
    }

    @Test
    void runningAgainIsHarmless() {
        ReadingPartitionMaintainer maintainer = maintainerAt("2033-03-10");

        maintainer.createPartitions();
        maintainer.createPartitions();

        assertThat(partitionExists("2033-03-10")).isTrue();
    }

    @Test
    void aDayBlockedByTheDefaultPartitionDoesNotStopTheOtherDays() {
        UUID readingId = insertReading("2034-03-10T07:00:00Z"); // no partition yet, so it lands in the default one
        try {
            maintainerAt("2034-03-10").createPartitions();

            assertThat(partitionExists("2034-03-10")).isFalse();
            assertThat(partitionExists("2034-03-11")).isTrue();
            assertThat(partitionOf(readingId)).isEqualTo("readings_default");
        } finally {
            jdbc.update("DELETE FROM readings WHERE reading_id = ?", readingId);
        }
    }

    private ReadingPartitionMaintainer maintainerAt(String day) {
        Clock clock = Clock.fixed(LocalDate.parse(day).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        return new ReadingPartitionMaintainer(jdbc, clock, 30, 30);
    }

    private boolean partitionExists(String day) {
        String name = ReadingPartitionMaintainer.partitionName(LocalDate.parse(day));
        return jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name);
    }

    private UUID insertReading(String measuredAt) {
        UUID readingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO readings (device_id, reading_id, patient_id, metric_type, systolic, diastolic, measured_at)
                VALUES ('bp-001', ?, 'patient-1', 'BLOOD_PRESSURE', 120, 80, ?)
                """, readingId, Timestamp.from(Instant.parse(measuredAt)));
        return readingId;
    }

    private String partitionOf(UUID readingId) {
        return jdbc.queryForObject(
                "SELECT tableoid::regclass::text FROM readings WHERE reading_id = ?", String.class, readingId);
    }
}
