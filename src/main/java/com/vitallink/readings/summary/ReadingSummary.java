package com.vitallink.readings.summary;

import java.time.Instant;

/**
 * A summary for a coach. {@code source} says who wrote {@code summary}: the model ({@code LLM}, advisory: check it
 * against the facts) or our own code ({@code FALLBACK}, when the model was unavailable). The facts are always ours.
 */
record ReadingSummary(
        String patientId,
        int days,
        String summary,
        SummaryService.Source source,
        SummaryFacts facts,
        Instant generatedAt) {}
