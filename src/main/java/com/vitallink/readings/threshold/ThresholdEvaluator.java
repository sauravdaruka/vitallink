package com.vitallink.readings.threshold;

import com.vitallink.readings.threshold.Threshold.Metric;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether a blood pressure reading breaks any of the patient's thresholds. Plain Java with no Spring or
 * database: it only compares numbers, so it is cheap enough to run on the ingest path and easy to unit test.
 */
public final class ThresholdEvaluator {

    private ThresholdEvaluator() {}

    /**
     * @return why the reading breaks its limits (e.g. "systolic 182 above 180"), including the limits in force at
     *     the time, or empty if it breaks none
     */
    public static Optional<String> evaluate(int systolic, int diastolic, Integer pulse, List<Threshold> thresholds) {
        List<String> reasons = thresholds.stream()
                .sorted(Comparator.comparing(Threshold::metric))
                .map(threshold -> describeBreach(threshold, valueOf(threshold.metric(), systolic, diastolic, pulse)))
                .filter(Objects::nonNull)
                .toList();
        return reasons.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", reasons));
    }

    private static Integer valueOf(Metric metric, int systolic, int diastolic, Integer pulse) {
        return switch (metric) {
            case SYSTOLIC -> systolic;
            case DIASTOLIC -> diastolic;
            case PULSE -> pulse;
        };
    }

    private static String describeBreach(Threshold threshold, Integer value) {
        String name = threshold.metric().name().toLowerCase();
        if (value == null) {
            return null;
        }
        if (threshold.above() != null && value > threshold.above()) {
            return "%s %d above %d".formatted(name, value, threshold.above());
        }
        if (threshold.below() != null && value < threshold.below()) {
            return "%s %d below %d".formatted(name, value, threshold.below());
        }
        return null;
    }
}
