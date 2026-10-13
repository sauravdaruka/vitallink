package com.vitallink.readings.summary;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The numbers a summary is built from, computed by our code, never by the model. They go into the prompt, back to
 * the coach, and into the fallback text, so the coach always sees the real figures.
 *
 * @param trend "rising", "falling" or "steady" (systolic, first half of the period vs the second), or "not enough
 *     data" with fewer than 4 readings
 */
record SummaryFacts(
        int days,
        int readingCount,
        Integer averageSystolic,
        Integer averageDiastolic,
        Integer highestSystolic,
        Integer lowestSystolic,
        int breaches,
        String trend) {

    /** A change of at least this much in average systolic between the two halves counts as a trend. */
    private static final int TREND_MMHG = 5;

    record Reading(Instant measuredAt, int systolic, int diastolic) {}

    static SummaryFacts from(int days, List<Reading> readings, int breaches) {
        if (readings.isEmpty()) {
            return new SummaryFacts(days, 0, null, null, null, null, breaches, "not enough data");
        }
        List<Reading> byTime = readings.stream()
                .sorted(Comparator.comparing(Reading::measuredAt))
                .toList();
        return new SummaryFacts(
                days,
                byTime.size(),
                average(byTime.stream().mapToInt(Reading::systolic).toArray()),
                average(byTime.stream().mapToInt(Reading::diastolic).toArray()),
                byTime.stream().mapToInt(Reading::systolic).max().orElseThrow(),
                byTime.stream().mapToInt(Reading::systolic).min().orElseThrow(),
                breaches,
                trend(byTime));
    }

    /** The fallback text: plain facts, no model involved. */
    String asText() {
        if (readingCount == 0) {
            return "No readings in the last %d days.".formatted(days);
        }
        return ("%d readings in the last %d days. Average %d/%d, systolic between %d and %d. %d readings broke a"
                        + " limit. Systolic trend: %s.")
                .formatted(
                        readingCount,
                        days,
                        averageSystolic,
                        averageDiastolic,
                        lowestSystolic,
                        highestSystolic,
                        breaches,
                        trend);
    }

    private static String trend(List<Reading> byTime) {
        if (byTime.size() < 4) {
            return "not enough data";
        }
        int half = byTime.size() / 2;
        int first = average(
                byTime.subList(0, half).stream().mapToInt(Reading::systolic).toArray());
        int second = average(byTime.subList(half, byTime.size()).stream()
                .mapToInt(Reading::systolic)
                .toArray());
        if (second - first >= TREND_MMHG) {
            return "rising";
        }
        return first - second >= TREND_MMHG ? "falling" : "steady";
    }

    private static int average(int[] values) {
        return (int) Math.round(Arrays.stream(values).average().orElseThrow());
    }
}
