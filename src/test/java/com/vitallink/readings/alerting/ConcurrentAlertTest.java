package com.vitallink.readings.alerting;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.readings.TestcontainersConfiguration;
import com.vitallink.readings.ingest.ReadingBreached;
import com.vitallink.readings.threshold.Threshold.Severity;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Several workers handle different breaches for a patient with no alert yet, at the same moment. Each sees "no open
 * alert" and tries to open one; the unique index lets only one succeed, and the others retry (as RabbitMQ would)
 * and update that alert.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConcurrentAlertTest {

    private static final int WORKERS = 8;

    @Autowired
    private AlertService alertService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void simultaneousFirstBreachesOpenExactlyOneAlert() throws Exception {
        String patientId = "patient-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO patient_coaches (patient_id, program, coach_id) VALUES (?, 'HYPERTENSION', 'coach-asha')",
                patientId);
        AtomicInteger rejectedByUniqueIndex = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(WORKERS)) {
            List<Future<?>> workers = new ArrayList<>();
            for (int i = 0; i < WORKERS; i++) {
                ReadingBreached event = breach(patientId, i);
                workers.add(pool.submit(() -> {
                    start.await();
                    handleWithRetries(event, rejectedByUniqueIndex);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get();
            }
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM alerts WHERE patient_id = ?", Integer.class, patientId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT breach_count FROM alerts WHERE patient_id = ?", Integer.class, patientId))
                .isEqualTo(WORKERS);
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM notifications n JOIN alerts a ON a.id = n.alert_id
                        WHERE a.patient_id = ?
                        """, Integer.class, patientId)).isEqualTo(1);
    }

    private void handleWithRetries(ReadingBreached event, AtomicInteger rejected) {
        for (int attempt = 1; ; attempt++) {
            try {
                alertService.handle(event);
                return;
            } catch (DataIntegrityViolationException e) {
                rejected.incrementAndGet();
                if (attempt == 5) {
                    throw e;
                }
            }
        }
    }

    private static ReadingBreached breach(String patientId, int i) {
        return new ReadingBreached(
                UUID.randomUUID(),
                "bp-test",
                UUID.randomUUID(),
                patientId,
                "BLOOD_PRESSURE",
                Instant.now().minus(1, ChronoUnit.HOURS).plusSeconds(i),
                170,
                95,
                72,
                Severity.WARNING,
                "systolic 170 above 160 (WARNING)");
    }
}
