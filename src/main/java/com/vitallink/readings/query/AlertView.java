package com.vitallink.readings.query;

import java.time.Instant;

/** One alert in a coach's queue. {@code reason} includes the limits in force when it was raised. */
record AlertView(
        long id,
        String patientId,
        Integer systolic,
        Integer diastolic,
        Integer pulse,
        String reason,
        Instant measuredAt,
        Instant createdAt) {}
