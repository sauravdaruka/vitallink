package com.vitallink.readings.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.readings.TestcontainersConfiguration;
import com.vitallink.readings.device.AuthenticatedDevice;
import com.vitallink.readings.ingest.ReadingResult.Status;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A device whose network drops can send the same reading again while the first attempt is still running. Only one
 * attempt may store it; the database's primary key decides, not a check in our code.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConcurrentRetryTest {

    private static final int ATTEMPTS = 8;

    @Autowired
    private ReadingIngestService ingestService;

    @Test
    void theSameReadingSentAtTheSameMomentIsStoredExactlyOnce() throws Exception {
        AuthenticatedDevice device = new AuthenticatedDevice("bp-001", "patient-001");
        ReadingInput reading = new ReadingInput(
                UUID.randomUUID(), "BLOOD_PRESSURE", 120, 80, 72, Instant.now().minus(1, ChronoUnit.HOURS));
        IngestRequest request = new IngestRequest(null, List.of(reading));
        CountDownLatch start = new CountDownLatch(1);

        List<Status> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS)) {
            List<Future<Status>> attempts = new ArrayList<>();
            for (int i = 0; i < ATTEMPTS; i++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    return ingestService.ingest(device, request).getFirst().status();
                }));
            }
            start.countDown();
            for (Future<Status> attempt : attempts) {
                outcomes.add(attempt.get());
            }
        }

        assertThat(outcomes).filteredOn(Status.ACCEPTED::equals).hasSize(1);
        assertThat(outcomes).filteredOn(Status.DUPLICATE::equals).hasSize(ATTEMPTS - 1);
    }
}
