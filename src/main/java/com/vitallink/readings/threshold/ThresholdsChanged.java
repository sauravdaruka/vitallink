package com.vitallink.readings.threshold;

import java.util.List;

/**
 * Published by the care-plan system, which owns thresholds (assumption A5), when a patient's limits change. It
 * carries the patient's full new set of limits, so applying it twice gives the same result.
 */
record ThresholdsChanged(String patientId, List<Threshold> thresholds) {

    static final String TYPE = "ThresholdsChanged";
}
