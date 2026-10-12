package com.vitallink.readings.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReadingValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-12T07:00:00Z");

    private final ReadingValidator validator = new ReadingValidator(Duration.ofDays(30));

    @Test
    void aNormalReadingIsValid() {
        assertThat(validator.problemWith(reading(120, 80, 72, NOW), NOW)).isEmpty();
    }

    @Test
    void pulseIsOptional() {
        assertThat(validator.problemWith(reading(120, 80, null, NOW), NOW)).isEmpty();
    }

    @Test
    void extremeButRealValuesAreValid() {
        assertThat(validator.problemWith(reading(250, 150, 180, NOW), NOW)).isEmpty();
    }

    @Test
    void readingIdIsRequired() {
        ReadingInput reading = new ReadingInput(null, "BLOOD_PRESSURE", 120, 80, 72, NOW);

        assertThat(validator.problemWith(reading, NOW)).contains("readingId is required");
    }

    @Test
    void onlyBloodPressureIsSupported() {
        ReadingInput reading = new ReadingInput(UUID.randomUUID(), "GLUCOSE", 120, 80, 72, NOW);

        assertThat(validator.problemWith(reading, NOW)).contains("metricType must be BLOOD_PRESSURE");
    }

    @Test
    void systolicMustBeHigherThanDiastolic() {
        assertThat(validator.problemWith(reading(80, 120, 72, NOW), NOW))
                .contains("systolic must be higher than diastolic");
    }

    @Test
    void impossibleValuesAreRejected() {
        assertThat(validator.problemWith(reading(400, 80, 72, NOW), NOW))
                .contains("systolic must be between 40 and 300");
    }

    @Test
    void readingsUpToThirtyDaysOldAreAccepted() {
        assertThat(validator.problemWith(reading(120, 80, 72, NOW.minus(Duration.ofDays(30))), NOW))
                .isEmpty();
    }

    @Test
    void readingsOlderThanThirtyDaysAreRejected() {
        assertThat(validator.problemWith(reading(120, 80, 72, NOW.minus(Duration.ofDays(31))), NOW))
                .contains("measuredAt is more than 30 days old");
    }

    private static ReadingInput reading(int systolic, int diastolic, Integer pulse, Instant measuredAt) {
        return new ReadingInput(UUID.randomUUID(), "BLOOD_PRESSURE", systolic, diastolic, pulse, measuredAt);
    }
}
