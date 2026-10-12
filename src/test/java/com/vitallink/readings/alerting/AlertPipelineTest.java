package com.vitallink.readings.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.vitallink.readings.TestcontainersConfiguration;
import com.vitallink.readings.ingest.ReadingBreached;
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
 * The whole path: ingest → outbox → relay → RabbitMQ → worker → alert and notification. Each test uses its own new
 * patient, with their own limits, so other tests can't interfere.
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
    void newPatientWithADeviceAndLimits() {
        String suffix = UUID.randomUUID().toString();
        patientId = "patient-" + suffix;
        apiKey = "key-" + suffix;
        String deviceId = "bp-" + suffix;
        jdbc.update("INSERT INTO devices (device_id, patient_id) VALUES (?, ?)", deviceId, patientId);
        jdbc.update("INSERT INTO device_api_keys (key_hash, device_id) VALUES (sha256(?::bytea), ?)", apiKey, deviceId);
        jdbc.update(
                "INSERT INTO thresholds (patient_id, metric, below, above) VALUES (?, 'SYSTOLIC', 90, 180)", patientId);
        measuredAt = Instant.now().minus(2, ChronoUnit.HOURS);
    }

    @Test
    void aBreachRaisesAnAlertAndNotifiesTheCoach() throws Exception {
        assignCoach("coach-asha");

        send(195, 95);

        await().atMost(WAIT).until(() -> alerts().size() == 1);
        assertThat(alerts().getFirst())
                .containsEntry("reason", "systolic 195 above 180")
                .containsEntry("coach_id", "coach-asha");
        await().atMost(WAIT).until(() -> notifiedCoaches().equals(List.of("coach-asha")));
    }

    @Test
    void aNormalReadingRaisesNoAlert() throws Exception {
        assignCoach("coach-asha");

        send(120, 80);
        send(195, 95); // a later breach, to know the worker has caught up

        await().atMost(WAIT).until(() -> alerts().size() == 1);
        assertThat(alerts().getFirst()).containsEntry("reason", "systolic 195 above 180");
    }

    @Test
    void everyBreachingReadingRaisesItsOwnAlert() throws Exception {
        assignCoach("coach-asha");

        send(185, 95);
        send(195, 100);

        await().atMost(WAIT).until(() -> alerts().size() == 2);
        await().atMost(WAIT).until(() -> notifiedCoaches().size() == 2);
    }

    @Test
    void aPatientWithoutACoachStillGetsAnAlertButNobodyIsNotified() throws Exception {
        send(195, 95);

        await().atMost(WAIT).until(() -> alerts().size() == 1);
        assertThat(alerts().getFirst()).containsEntry("coach_id", null);
        assertThat(notifiedCoaches()).isEmpty();
    }

    @Test
    void theSameEventDeliveredTwiceRaisesOneAlertAndOneNotification() {
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
                "systolic 195 above 180");

        alertService.handle(event);
        alertService.handle(event);

        assertThat(alerts()).hasSize(1);
        assertThat(notifiedCoaches()).containsExactly("coach-asha");
    }

    private void assignCoach(String coachId) {
        jdbc.update("INSERT INTO patient_coaches (patient_id, coach_id) VALUES (?, ?)", patientId, coachId);
    }

    private void send(int systolic, int diastolic) throws Exception {
        measuredAt = measuredAt.plusSeconds(60);
        String body = """
                {"readings": [{"readingId": "%s", "metricType": "BLOOD_PRESSURE", "systolic": %d, "diastolic": %d,
                  "measuredAt": "%s"}]}
                """.formatted(UUID.randomUUID(), systolic, diastolic, measuredAt);
        mvc.perform(MockMvcRequestBuilders.post("/v1/readings")
                .header("X-Device-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private List<Map<String, Object>> alerts() {
        return jdbc.queryForList("SELECT reason, coach_id FROM alerts WHERE patient_id = ? ORDER BY id", patientId);
    }

    private List<String> notifiedCoaches() {
        return jdbc.queryForList("""
                SELECT n.coach_id FROM notifications n JOIN alerts a ON a.id = n.alert_id
                WHERE a.patient_id = ? ORDER BY n.id
                """, String.class, patientId);
    }
}
