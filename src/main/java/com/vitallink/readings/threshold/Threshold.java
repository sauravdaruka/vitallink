package com.vitallink.readings.threshold;

/** One limit for one metric. A value below {@code below} or above {@code above} breaks it; either may be null. */
public record Threshold(Metric metric, Integer below, Integer above) {

    public enum Metric {
        SYSTOLIC,
        DIASTOLIC,
        PULSE
    }
}
