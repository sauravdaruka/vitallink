package com.vitallink.readings.threshold;

/**
 * One limit for one metric. A value below {@code below} or above {@code above} breaks it; either may be null.
 *
 * @param patientSpecific true for a patient's own limit, false for a program-wide default
 */
public record Threshold(Metric metric, Severity severity, Integer below, Integer above, boolean patientSpecific) {

    public enum Metric {
        SYSTOLIC,
        DIASTOLIC,
        PULSE
    }

    /** Declared from least to most severe, so the higher ordinal wins. */
    public enum Severity {
        WARNING,
        CRITICAL
    }
}
