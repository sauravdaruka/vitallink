package com.vitallink.readings.threshold;

import com.vitallink.readings.threshold.Threshold.Metric;
import com.vitallink.readings.threshold.Threshold.Severity;

/** One limit as the admin API sends and returns it. A value below {@code below} or above {@code above} breaks it. */
record ThresholdLimit(Metric metric, Severity severity, Integer below, Integer above) {

    static ThresholdLimit of(Threshold threshold) {
        return new ThresholdLimit(threshold.metric(), threshold.severity(), threshold.below(), threshold.above());
    }
}
