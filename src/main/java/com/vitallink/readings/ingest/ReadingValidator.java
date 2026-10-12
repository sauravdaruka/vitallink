package com.vitallink.readings.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Checks one reading. Plain Java with no Spring or database, so every rule is easy to unit test.
 *
 * <p>The value ranges match the CHECK constraints in V1__create_readings.sql. They are wide on purpose: they
 * stop impossible values, never a real but extreme reading.
 */
final class ReadingValidator {

    private final Duration maxAge;

    ReadingValidator(Duration maxAge) {
        this.maxAge = maxAge;
    }

    /** Why the reading can't be stored, or empty if it's valid. */
    Optional<String> problemWith(ReadingInput reading, Instant now) {
        if (reading.readingId() == null) {
            return Optional.of("readingId is required");
        }
        if (reading.measuredAt() == null) {
            return Optional.of("measuredAt is required");
        }
        if (!"BLOOD_PRESSURE".equals(reading.metricType())) {
            return Optional.of("metricType must be BLOOD_PRESSURE");
        }
        if (reading.systolic() == null || reading.diastolic() == null) {
            return Optional.of("systolic and diastolic are required");
        }
        if (outside(reading.systolic(), 40, 300)) {
            return Optional.of("systolic must be between 40 and 300");
        }
        if (outside(reading.diastolic(), 20, 200)) {
            return Optional.of("diastolic must be between 20 and 200");
        }
        if (reading.pulse() != null && outside(reading.pulse(), 20, 250)) {
            return Optional.of("pulse must be between 20 and 250");
        }
        if (reading.systolic() <= reading.diastolic()) {
            return Optional.of("systolic must be higher than diastolic");
        }
        if (reading.measuredAt().isBefore(now.minus(maxAge))) {
            return Optional.of("measuredAt is more than %d days old".formatted(maxAge.toDays()));
        }
        return Optional.empty();
    }

    private static boolean outside(int value, int min, int max) {
        return value < min || value > max;
    }
}
