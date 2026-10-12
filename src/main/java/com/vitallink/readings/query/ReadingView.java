package com.vitallink.readings.query;

import java.time.Instant;
import java.util.UUID;

/** One stored reading. {@code receivedAt} is when we got it; a big gap from {@code measuredAt} means a late upload. */
record ReadingView(
        UUID readingId,
        String deviceId,
        String metricType,
        Integer systolic,
        Integer diastolic,
        Integer pulse,
        Instant measuredAt,
        Instant receivedAt) {}
