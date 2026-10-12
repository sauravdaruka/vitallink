package com.vitallink.readings.threshold;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.readings.threshold.Threshold.Metric;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Patient-safety logic, so the edges are tested exactly: a limit itself is not a breach. */
class ThresholdEvaluatorTest {

    private static final List<Threshold> LIMITS = List.of(
            new Threshold(Metric.SYSTOLIC, 90, 180),
            new Threshold(Metric.DIASTOLIC, 50, 110),
            new Threshold(Metric.PULSE, 40, 130));

    @Test
    void aNormalReadingBreaksNothing() {
        assertThat(ThresholdEvaluator.evaluate(120, 80, 72, LIMITS)).isEmpty();
    }

    @Test
    void exactlyOnTheLimitIsNotABreach() {
        assertThat(ThresholdEvaluator.evaluate(180, 110, 130, LIMITS)).isEmpty();
        assertThat(ThresholdEvaluator.evaluate(90, 50, 40, LIMITS)).isEmpty();
    }

    @Test
    void aboveTheUpperLimitIsABreach() {
        assertThat(ThresholdEvaluator.evaluate(181, 80, 72, LIMITS)).contains("systolic 181 above 180");
    }

    @Test
    void belowTheLowerLimitIsABreach() {
        assertThat(ThresholdEvaluator.evaluate(85, 55, 72, LIMITS)).contains("systolic 85 below 90");
    }

    @Test
    void everyBrokenLimitIsInTheReason() {
        assertThat(ThresholdEvaluator.evaluate(195, 115, 140, LIMITS))
                .contains("systolic 195 above 180; diastolic 115 above 110; pulse 140 above 130");
    }

    @Test
    void aMissingPulseIsNotABreach() {
        assertThat(ThresholdEvaluator.evaluate(120, 80, null, LIMITS)).isEmpty();
    }

    @Test
    void aPatientWithNoThresholdsGetsNoBreach() {
        assertThat(ThresholdEvaluator.evaluate(250, 150, 200, List.of())).isEmpty();
    }
}
