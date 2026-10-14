package com.vitallink.readings.summary;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A plain-language summary of a patient's recent readings for a coach (Part C). The model is advisory and must
 * never make the request fail or hang:
 *
 * <ul>
 *   <li>the facts are computed here, so the coach always gets the real numbers;
 *   <li>the model gets numbers and day counts only, never a patient ID or name;
 *   <li>a call is cut off after a timeout, and at most a few calls run at once (the provider's rate limit, and our
 *       cost); a request over the limit gets the fallback immediately instead of waiting;
 *   <li>anything going wrong gives the fallback: the same facts as plain text. No retries while a person waits.
 * </ul>
 *
 * This endpoint is only on the read path; ingest and alerting never call it.
 */
@Service
class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private final JdbcTemplate jdbc;
    private final LlmClient llm;
    private final Clock clock;
    private final Duration timeout;
    private final int maxConcurrentCalls;
    private final Semaphore callSlots;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    SummaryService(
            JdbcTemplate jdbc,
            LlmClient llm,
            Clock clock,
            @Value("${summary.llm.timeout}") Duration timeout,
            @Value("${summary.llm.max-concurrent-calls}") int maxConcurrentCalls) {
        this.jdbc = jdbc;
        this.llm = llm;
        this.clock = clock;
        this.timeout = timeout;
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.callSlots = new Semaphore(maxConcurrentCalls);
    }

    ReadingSummary summarize(String patientId, int days) {
        Instant now = clock.instant();
        Instant from = now.minus(Duration.ofDays(days));
        SummaryFacts facts = SummaryFacts.from(days, readings(patientId, from), breaches(patientId, from));
        if (facts.readingCount() == 0) {
            return new ReadingSummary(patientId, days, facts.asText(), Source.FALLBACK, facts, now);
        }
        String text = askModel(prompt(facts));
        return text == null
                ? new ReadingSummary(patientId, days, facts.asText(), Source.FALLBACK, facts, now)
                : new ReadingSummary(patientId, days, text, Source.LLM, facts, now);
    }

    /** The model's text, or null if it was busy, too slow, failed, or returned nothing. */
    private String askModel(String prompt) {
        if (!callSlots.tryAcquire()) {
            log.warn("Summary model busy: {} calls already running; using the fallback", maxConcurrentCalls);
            return null;
        }
        CompletableFuture<String> call = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return llm.complete(prompt);
                    } finally {
                        // Released when the call really ends, so a slow provider still counts against the limit.
                        callSlots.release();
                    }
                },
                calls);
        try {
            String text = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return text == null || text.isBlank() ? null : text.strip();
        } catch (TimeoutException e) {
            call.cancel(true);
            log.warn("Summary model took longer than {}; using the fallback", timeout);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            log.warn("Summary model failed; using the fallback", e.getCause());
            return null;
        }
    }

    /** Numbers and day counts only: no patient ID, name or other identifier goes to the model. */
    private static String prompt(SummaryFacts facts) {
        return """
                You help a care coach review a patient's home blood pressure readings.
                Write two or three plain sentences about the facts below: the overall level, the trend, and any
                readings that broke a limit. Use only these facts. Do not diagnose or recommend treatment.
                Facts: %d readings over the last %d days; average %d/%d mmHg; systolic from %d to %d;
                %d readings broke a limit; systolic trend: %s.
                """.formatted(
                        facts.readingCount(),
                        facts.days(),
                        facts.averageSystolic(),
                        facts.averageDiastolic(),
                        facts.lowestSystolic(),
                        facts.highestSystolic(),
                        facts.breaches(),
                        facts.trend());
    }

    private List<SummaryFacts.Reading> readings(String patientId, Instant from) {
        return jdbc.query(
                """
                SELECT measured_at, systolic, diastolic FROM readings
                WHERE patient_id = ? AND measured_at >= ?
                ORDER BY measured_at
                """,
                (row, _) -> new SummaryFacts.Reading(
                        row.getTimestamp("measured_at").toInstant(), row.getInt("systolic"), row.getInt("diastolic")),
                patientId,
                Timestamp.from(from));
    }

    private int breaches(String patientId, Instant from) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM alerts WHERE patient_id = ? AND measured_at >= ?",
                Integer.class,
                patientId,
                Timestamp.from(from));
        return count == null ? 0 : count;
    }

    enum Source {
        LLM,
        FALLBACK
    }
}
