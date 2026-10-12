package com.vitallink.readings.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.vitallink.readings.TestcontainersConfiguration;
import com.vitallink.readings.ingest.ReadingBreached;
import com.vitallink.readings.threshold.Threshold.Severity;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The whole path: ingest → outbox → relay → RabbitMQ → worker → alert, breach history and notification. Each test
 * uses its own new patient, so open alerts from other tests can't interfere.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AlertPipelineTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AlertService alertService;

    private String patientId;
    private String apiKey;
    private Instant measuredAt;

    @BeforeEach
    void newPatientWithADevice() {
        String suffix = UUID.randomUUID().toString();
        patientId = "patient-" + suffix;
        apiKey = "key-" + suffix;
        String deviceId = "bp-" + suffix;
        jdbc.update("INSERT INTO devices (device_id, patient_id) VALUES (?, ?)", deviceId, patientId);
        jdbc.update("INSERT INTO device_api_keys (key_hash, device_id) VALUES (sha256(?::bytea), ?)", apiKey, deviceId);
        measuredAt = Instant.now().minus(2, ChronoUnit.HOURS);
    }

    @Test
    void aFirstBreachOpensAnAlertAndNotifiesTheCoach() throws Exception {
        assignCoach("coach-asha");

        send(165, 95);

        await().atMost(WAIT).until(() -> alerts().size() == 1);
        assertThat(alerts().getFirst())
                .containsEntry("severity", "WARNING")
                .containsEntry("status", "OPEN")
                .containsEntry("assigned_coach_id", "coach-asha")
                .containsEntry("breach_count", 1);
        await().atMost(WAIT).until(() -> notificationKinds().equals(List.of("OPENED")));
    }

    @Test
    void repeatsUpdateTheSameAlertAndOnlyAnEscalationNotifiesAgain() throws Exception {
        assignCoach("coach-asha");

        send(165, 95);
        await().atMost(WAIT).until(() -> breachCount() == 1);
        send(170, 96);
        await().atMost(WAIT).until(() -> breachCount() == 2);
        assertThat(notificationKinds()).containsExactly("OPENED");

        send(195, 115);
        await().atMost(WAIT).until(() -> breachCount() == 3);

        assertThat(alerts()).hasSize(1);
        assertThat(alerts().getFirst()).containsEntry("severity", "CRITICAL");
        assertThat(notificationKinds()).containsExactly("OPENED", "ESCALATED");
        assertThat(breachHistory()).containsExactly("WARNING", "WARNING", "CRITICAL");
    }

    @Test
    void manyBreachesArrivingTogetherEndUpInOneAlert() throws Exception {
        assignCoach("coach-asha");
        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            batch.append(i == 0 ? "" : ",").append(reading(170, 95, measuredAt.plusSeconds(i)));
        }

        post("{\"readings\": [" + batch + "]}");

        await().atMost(WAIT).until(() -> breachCount() == 10);
        assertThat(alerts()).hasSize(1);
        assertThat(notificationKinds()).containsExactly("OPENED");
    }

    @Test
    void aPatientWithoutACoachGetsAnUnassignedAlertAndNoNotification() throws Exception {
        send(195, 115);

        await().atMost(WAIT).until(() -> alerts().size() == 1);
        assertThat(alerts().getFirst()).containsEntry("assigned_coach_id", null);
        assertThat(notificationKinds()).isEmpty();
    }

    @Test
    void aBreachAfterTheAlertIsResolvedOpensANewAlert() throws Exception {
        assignCoach("coach-asha");
        send(165, 95);
        await().atMost(WAIT).until(() -> alerts().size() == 1);
        jdbc.update("UPDATE alerts SET status = 'RESOLVED' WHERE patient_id = ?", patientId);

        send(165, 95);

        await().atMost(WAIT).until(() -> alerts().size() == 2);
        assertThat(notificationKinds()).containsExactly("OPENED", "OPENED");
    }

    @Test
    void theSameEventDeliveredTwiceIsHandledOnce() {
        assignCoach("coach-asha");
        ReadingBreached event = new ReadingBreached(
                UUID.randomUUID(),
                "bp-test",
                UUID.randomUUID(),
                patientId,
                "BLOOD_PRESSURE",
                measuredAt,
                195,
                115,
                72,
                Severity.CRITICAL,
                "systolic 195 above 180 (CRITICAL)");

        alertService.handle(event);
        alertService.handle(event);

        assertThat(breachCount()).isEqualTo(1);
        assertThat(notificationKinds()).containsExactly("OPENED");
    }

    private void assignCoach(String coachId) {
        jdbc.update(
                "INSERT INTO patient_coaches (patient_id, program, coach_id) VALUES (?, 'HYPERTENSION', ?)",
                patientId,
                coachId);
    }

    private void send(int systolic, int diastolic) throws Exception {
        measuredAt = measuredAt.plusSeconds(60);
        post("{\"readings\": [" + reading(systolic, diastolic, measuredAt) + "]}");
    }

    private void post(String body) throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/v1/readings")
                .header("X-Device-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String reading(int systolic, int diastolic, Instant at) {
        return """
                {"readingId": "%s", "metricType": "BLOOD_PRESSURE", "systolic": %d, "diastolic": %d, "measuredAt": "%s"}
                """.formatted(UUID.randomUUID(), systolic, diastolic, at);
    }

    private List<Map<String, Object>> alerts() {
        return jdbc.queryForList(
                "SELECT severity, status, assigned_coach_id, breach_count FROM alerts WHERE patient_id = ? ORDER BY id",
                patientId);
    }

    private int breachCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM alert_breaches b JOIN alerts a ON a.id = b.alert_id WHERE a.patient_id = ?
                """, Integer.class, patientId);
    }

    private List<String> breachHistory() {
        return jdbc.queryForList("""
                SELECT b.severity FROM alert_breaches b JOIN alerts a ON a.id = b.alert_id
                WHERE a.patient_id = ? ORDER BY b.measured_at
                """, String.class, patientId);
    }

    private List<String> notificationKinds() {
        return jdbc.queryForList("""
                SELECT n.kind FROM notifications n JOIN alerts a ON a.id = n.alert_id
                WHERE a.patient_id = ? ORDER BY n.id
                """, String.class, patientId);
    }
}
