package com.vitallink.readings.ingest;

import java.time.Instant;
import java.util.UUID;

/** One reading as the device sends it. Fields can be missing, so each one is checked by {@link ReadingValidator}. */
record ReadingInput(
        UUID readingId, String metricType, Integer systolic, Integer diastolic, Integer pulse, Instant measuredAt) {}
