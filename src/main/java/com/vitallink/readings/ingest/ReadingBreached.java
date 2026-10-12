package com.vitallink.readings.ingest;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a newly stored reading breaks a threshold. Ingest owns this event; alerting consumes it.
 *
 * @param eventId unique per event, so a consumer can recognise the same event delivered twice
 * @param reason the limits that were broken, as they were at that moment
 */
public record ReadingBreached(
        UUID eventId,
        String deviceId,
        UUID readingId,
        String patientId,
        String metricType,
        Instant measuredAt,
        int systolic,
        int diastolic,
        Integer pulse,
        String reason) {

    public static final String TYPE = "ReadingBreached";
}
