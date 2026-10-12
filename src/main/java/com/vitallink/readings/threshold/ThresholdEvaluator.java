package com.vitallink.readings.threshold;

import com.vitallink.readings.threshold.Threshold.Metric;
import com.vitallink.readings.threshold.Threshold.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides whether a blood pressure reading breaks any threshold. Plain Java with no Spring or database: it only
 * compares numbers, so it is cheap enough to run on the ingest path and easy to unit test.
 */
public final class ThresholdEvaluator {

    /** What was broken. The reason includes the limits, so the alert records the limits in force at the time. */
    public record Breach(Severity severity, String reason) {}

    private ThresholdEvaluator() {}

    public static Optional<Breach> evaluate(int systolic, int diastolic, Integer pulse, List<Threshold> thresholds) {
        List<Threshold> applicable = applicable(thresholds);
        List<String> reasons = new ArrayList<>();
        Severity worst = null;
        for (Severity severity : List.of(Severity.CRITICAL, Severity.WARNING)) {
            for (Threshold threshold : applicable) {
                if (threshold.severity() != severity) {
                    continue;
                }
                Integer value = valueOf(threshold.metric(), systolic, diastolic, pulse);
                Optional<String> broken = describeBreach(threshold, value);
                if (broken.isPresent() && (worst == null || worst == severity)) {
                    worst = severity;
                    reasons.add(broken.get());
                }
            }
        }
        return worst == null ? Optional.empty() : Optional.of(new Breach(worst, String.join("; ", reasons)));
    }

    /** A patient's own limits for a metric replace the program defaults for that metric only. */
    private static List<Threshold> applicable(List<Threshold> thresholds) {
        Set<Metric> overridden = thresholds.stream()
                .filter(Threshold::patientSpecific)
                .map(Threshold::metric)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Metric.class)));
        return thresholds.stream()
                .filter(t -> t.patientSpecific() || !overridden.contains(t.metric()))
                .sorted(Comparator.comparing(Threshold::metric))
                .toList();
    }

    private static Integer valueOf(Metric metric, int systolic, int diastolic, Integer pulse) {
        return switch (metric) {
            case SYSTOLIC -> systolic;
            case DIASTOLIC -> diastolic;
            case PULSE -> pulse;
        };
    }

    private static Optional<String> describeBreach(Threshold threshold, Integer value) {
        String name = threshold.metric().name().toLowerCase();
        if (value == null) {
            return Optional.empty();
        }
        if (threshold.above() != null && value > threshold.above()) {
            return Optional.of("%s %d above %d (%s)".formatted(name, value, threshold.above(), threshold.severity()));
        }
        if (threshold.below() != null && value < threshold.below()) {
            return Optional.of("%s %d below %d (%s)".formatted(name, value, threshold.below(), threshold.severity()));
        }
        return Optional.empty();
    }
}
