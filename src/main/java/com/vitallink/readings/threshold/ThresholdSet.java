package com.vitallink.readings.threshold;

import java.util.List;

/** The body of the admin API, in both directions: the full set of limits for one scope. */
record ThresholdSet(List<ThresholdLimit> thresholds) {}
