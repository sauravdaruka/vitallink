package com.vitallink.readings.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.readings.TestcontainersConfiguration;
import com.vitallink.readings.device.AuthenticatedDevice;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Uses the seeded thresholds: program defaults, plus stricter systolic limits for patient-002. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BreachEventTest {

    private static final AuthenticatedDevice RAVI = new AuthenticatedDevice("bp-001", "patient-001");
    private static final AuthenticatedDevice MEENA = new AuthenticatedDevice("bp-002", "patient-002");

    // A real retry resends the reading unchanged, including measuredAt (assumption A10).
    private final Instant measuredAt = Instant.now().minus(1, ChronoUnit.HOURS);

    @Autowired
    private ReadingIngestService ingestService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aNormalReadingRaisesNoEvent() {
        UUID readingId = send(RAVI, 120, 80);

        assertThat(eventsFor(readingId)).isEmpty();
    }

    @Test
    void aBreachingReadingRaisesOneEventWithTheLimitsInForce() {
        UUID readingId = send(RAVI, 182, 90);

        List<Map<String, Object>> events = eventsFor(readingId);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst())
                .containsEntry("event_type", "ReadingBreached")
                .containsEntry("severity", "CRITICAL")
                .containsEntry("reason", "systolic 182 above 180 (CRITICAL)")
                .containsEntry("patient_id", "patient-001");
    }

    @Test
    void retryingABreachingReadingDoesNotRaiseASecondEvent() {
        UUID readingId = UUID.randomUUID();

        send(RAVI, readingId, 182, 90);
        send(RAVI, readingId, 182, 90);

        assertThat(eventsFor(readingId)).hasSize(1);
    }

    @Test
    void aPatientsOwnLimitsAreUsed() {
        UUID readingId = send(MEENA, 175, 90);

        assertThat(eventsFor(readingId).getFirst()).containsEntry("reason", "systolic 175 above 170 (CRITICAL)");
    }

    private UUID send(AuthenticatedDevice device, int systolic, int diastolic) {
        return send(device, UUID.randomUUID(), systolic, diastolic);
    }

    private UUID send(AuthenticatedDevice device, UUID readingId, int systolic, int diastolic) {
        ReadingInput reading = new ReadingInput(readingId, "BLOOD_PRESSURE", systolic, diastolic, 72, measuredAt);
        ingestService.ingest(device, new IngestRequest(null, List.of(reading)));
        return readingId;
    }

    private List<Map<String, Object>> eventsFor(UUID readingId) {
        return jdbc.queryForList("""
                SELECT event_type, payload->>'severity' AS severity, payload->>'reason' AS reason,
                       payload->>'patientId' AS patient_id
                FROM outbox
                WHERE payload->>'readingId' = ?
                """, readingId.toString());
    }
}
