package com.vitallink.readings.threshold;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.readings.threshold.Threshold.Metric;
import com.vitallink.readings.threshold.Threshold.Severity;
import com.vitallink.readings.threshold.ThresholdEvaluator.Breach;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThresholdEvaluatorTest {

    private static final List<Threshold> DEFAULTS = List.of(
            new Threshold(Metric.SYSTOLIC, Severity.CRITICAL, 90, 180, false),
            new Threshold(Metric.SYSTOLIC, Severity.WARNING, 100, 160, false),
            new Threshold(Metric.DIASTOLIC, Severity.CRITICAL, 50, 110, false),
            new Threshold(Metric.PULSE, Severity.CRITICAL, 40, 130, false));

    @Test
    void aNormalReadingBreaksNothing() {
        assertThat(ThresholdEvaluator.evaluate(120, 80, 72, DEFAULTS)).isEmpty();
    }

    @Test
    void aValueOnTheLimitIsNotABreach() {
        assertThat(ThresholdEvaluator.evaluate(180, 80, 72, DEFAULTS))
                .contains(new Breach(Severity.WARNING, "systolic 180 above 160 (WARNING)"));
    }

    @Test
    void aWarningBreach() {
        assertThat(ThresholdEvaluator.evaluate(165, 80, 72, DEFAULTS))
                .contains(new Breach(Severity.WARNING, "systolic 165 above 160 (WARNING)"));
    }

    @Test
    void theMostSevereBreachWinsAndOnlyItIsReported() {
        assertThat(ThresholdEvaluator.evaluate(185, 80, 72, DEFAULTS))
                .contains(new Breach(Severity.CRITICAL, "systolic 185 above 180 (CRITICAL)"));
    }

    @Test
    void everyBreachAtTheWorstSeverityIsListed() {
        assertThat(ThresholdEvaluator.evaluate(185, 115, 72, DEFAULTS))
                .contains(new Breach(
                        Severity.CRITICAL, "systolic 185 above 180 (CRITICAL); diastolic 115 above 110 (CRITICAL)"));
    }

    @Test
    void aLowValueIsABreachToo() {
        assertThat(ThresholdEvaluator.evaluate(85, 55, 72, DEFAULTS))
                .contains(new Breach(Severity.CRITICAL, "systolic 85 below 90 (CRITICAL)"));
    }

    @Test
    void aMissingPulseIsNotABreach() {
        assertThat(ThresholdEvaluator.evaluate(120, 80, null, DEFAULTS)).isEmpty();
    }

    @Test
    void aPatientsOwnLimitReplacesTheDefaultForThatMetricOnly() {
        List<Threshold> withOwnSystolic = new java.util.ArrayList<>(DEFAULTS);
        withOwnSystolic.add(new Threshold(Metric.SYSTOLIC, Severity.CRITICAL, 90, 170, true));

        assertThat(ThresholdEvaluator.evaluate(175, 80, 72, withOwnSystolic))
                .contains(new Breach(Severity.CRITICAL, "systolic 175 above 170 (CRITICAL)"));
        // The default systolic WARNING (above 160) no longer applies to this patient.
        assertThat(ThresholdEvaluator.evaluate(165, 80, 72, withOwnSystolic)).isEmpty();
        // Diastolic still uses the default.
        assertThat(ThresholdEvaluator.evaluate(120, 115, 72, withOwnSystolic))
                .contains(new Breach(Severity.CRITICAL, "diastolic 115 above 110 (CRITICAL)"));
    }
}
