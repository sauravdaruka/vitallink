package com.vitallink.readings.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SummaryFactsTest {

    private static final Instant START = Instant.parse("2026-10-01T07:00:00Z");

    @Test
    void averagesRangeAndARisingTrendAreComputed() {
        SummaryFacts facts = SummaryFacts.from(7, readings(130, 132, 150, 152), 1);

        assertThat(facts.readingCount()).isEqualTo(4);
        assertThat(facts.averageSystolic()).isEqualTo(141);
        assertThat(facts.lowestSystolic()).isEqualTo(130);
        assertThat(facts.highestSystolic()).isEqualTo(152);
        assertThat(facts.trend()).isEqualTo("rising");
        assertThat(facts.asText())
                .isEqualTo("4 readings in the last 7 days. Average 141/80, systolic between 130 and 152."
                        + " 1 readings broke a limit. Systolic trend: rising.");
    }

    @Test
    void aSmallChangeIsSteadyAndFewReadingsAreNotEnoughForATrend() {
        assertThat(SummaryFacts.from(7, readings(140, 141, 142, 143), 0).trend())
                .isEqualTo("steady");
        assertThat(SummaryFacts.from(7, readings(130, 160), 0).trend()).isEqualTo("not enough data");
    }

    @Test
    void noReadingsSaySo() {
        assertThat(SummaryFacts.from(7, List.of(), 0).asText()).isEqualTo("No readings in the last 7 days.");
    }

    /** One reading a day, in the order given. */
    private static List<SummaryFacts.Reading> readings(int... systolic) {
        return java.util.stream.IntStream.range(0, systolic.length)
                .mapToObj(i -> new SummaryFacts.Reading(START.plusSeconds(86_400L * i), systolic[i], 80))
                .toList();
    }
}
