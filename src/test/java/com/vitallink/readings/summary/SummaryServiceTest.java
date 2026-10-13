package com.vitallink.readings.summary;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.readings.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real readings in PostgreSQL, with stand-in models that answer, hang, fail or are busy. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SummaryServiceTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);

    @Autowired
    private JdbcTemplate jdbc;

    private String patientId;

    @BeforeEach
    void aPatientWithFourReadings() {
        patientId = "patient-" + UUID.randomUUID();
        Instant now = Instant.now();
        int[] systolic = {130, 132, 150, 152};
        for (int i = 0; i < systolic.length; i++) {
            jdbc.update(
                    """
                    INSERT INTO readings (device_id, reading_id, patient_id, metric_type, systolic, diastolic, measured_at)
                    VALUES ('bp-test', ?, ?, 'BLOOD_PRESSURE', ?, 80, ?)
                    """, UUID.randomUUID(), patientId, systolic[i], Timestamp.from(now.minus(4 - i, ChronoUnit.DAYS)));
        }
    }

    @Test
    void theModelsTextIsUsedAndThePromptHasNoPatientId() {
        AtomicReference<String> prompt = new AtomicReference<>();
        SummaryService service = service(
                p -> {
                    prompt.set(p);
                    return "BP is trending up over the week.";
                },
                4);

        ReadingSummary summary = service.summarize(patientId, 7);

        assertThat(summary.source()).isEqualTo(SummaryService.Source.LLM);
        assertThat(summary.summary()).isEqualTo("BP is trending up over the week.");
        assertThat(summary.facts().trend()).isEqualTo("rising");
        assertThat(prompt.get()).contains("4 readings").doesNotContain(patientId);
    }

    @Test
    void aSlowModelIsCutOffAndTheCoachGetsTheFactsInstead() {
        SummaryService service = service(p -> sleep(Duration.ofSeconds(5)), 4);

        long start = System.nanoTime();
        ReadingSummary summary = service.summarize(patientId, 7);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(summary.source()).isEqualTo(SummaryService.Source.FALLBACK);
        assertThat(summary.summary()).startsWith("4 readings in the last 7 days");
    }

    @Test
    void aFailingModelGivesTheFallback() {
        SummaryService service = service(
                p -> {
                    throw new IllegalStateException("429 Too Many Requests");
                },
                4);

        assertThat(service.summarize(patientId, 7).source()).isEqualTo(SummaryService.Source.FALLBACK);
    }

    @Test
    void whenAllCallSlotsAreBusyTheFallbackComesBackAtOnce() throws Exception {
        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SummaryService service = service(
                p -> {
                    inCall.countDown();
                    await(release);
                    return "late";
                },
                1);
        CompletableFuture<ReadingSummary> first = CompletableFuture.supplyAsync(() -> service.summarize(patientId, 7));
        assertThat(inCall.await(5, TimeUnit.SECONDS)).isTrue();

        ReadingSummary second = service.summarize(patientId, 7);

        assertThat(second.source()).isEqualTo(SummaryService.Source.FALLBACK);
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
    }

    @Test
    void withNoReadingsTheModelIsNotCalled() {
        SummaryService service = service(
                p -> {
                    throw new AssertionError("the model must not be called");
                },
                4);

        ReadingSummary summary = service.summarize("patient-with-no-readings", 7);

        assertThat(summary.source()).isEqualTo(SummaryService.Source.FALLBACK);
        assertThat(summary.summary()).isEqualTo("No readings in the last 7 days.");
    }

    private SummaryService service(LlmClient llm, int maxConcurrentCalls) {
        return new SummaryService(jdbc, llm, Clock.systemUTC(), TIMEOUT, maxConcurrentCalls);
    }

    private static String sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "too late";
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
