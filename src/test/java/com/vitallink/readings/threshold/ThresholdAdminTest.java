package com.vitallink.readings.threshold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vitallink.readings.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

/** Each test uses its own new patient and device, so changed limits can't affect other tests. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ThresholdAdminTest {

    private static final String ADMIN_KEY = "test-admin-key";
    private static final String STRICTER_SYSTOLIC =
            "{\"thresholds\": [{\"metric\": \"SYSTOLIC\", \"severity\": \"WARNING\", \"above\": 140}]}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private Thresholds thresholds;

    @Autowired
    private JsonMapper json;

    private String patientId;
    private String deviceKey;

    @BeforeEach
    void newPatientWithADevice() {
        String suffix = UUID.randomUUID().toString();
        patientId = "patient-" + suffix;
        deviceKey = "key-" + suffix;
        jdbc.update("INSERT INTO devices (device_id, patient_id) VALUES (?, ?)", "bp-" + suffix, patientId);
        jdbc.update(
                "INSERT INTO device_api_keys (key_hash, device_id) VALUES (sha256(?::bytea), ?)",
                deviceKey,
                "bp-" + suffix);
    }

    @Test
    void aMissingOrWrongAdminKeyIsRejected() throws Exception {
        putPatientLimits(STRICTER_SYSTOLIC, null).andExpect(status().isUnauthorized());
        putPatientLimits(STRICTER_SYSTOLIC, "not-the-key").andExpect(status().isUnauthorized());
    }

    @Test
    void invalidLimitsAreRejected() throws Exception {
        putPatientLimits(
                        "{\"thresholds\": [{\"metric\": \"SYSTOLIC\", \"severity\": \"WARNING\", \"below\": 150, \"above\": 140}]}",
                        ADMIN_KEY)
                .andExpect(status().isBadRequest());
        put("/v1/admin/thresholds/defaults", "{\"thresholds\": []}", ADMIN_KEY).andExpect(status().isBadRequest());
    }

    @Test
    void aChangeIsUsedByTheVeryNextReading() throws Exception {
        UUID before = sendReading(150);
        assertThat(breachReason(before)).isEmpty(); // 150 is under the default limit, and the cache is now warm

        putPatientLimits(STRICTER_SYSTOLIC, ADMIN_KEY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thresholds[0].above").value(140));

        UUID after = sendReading(150);
        assertThat(breachReason(after)).containsExactly("systolic 150 above 140 (WARNING)");
    }

    @Test
    void anOldCopyWrittenJustAfterTheChangeIsRemovedByTheOutboxDelete() throws Exception {
        putPatientLimits(STRICTER_SYSTOLIC, ADMIN_KEY).andExpect(status().isOk());

        // A slow request that read the limits before the change writes them into the cache after the first delete.
        String key = ThresholdCache.keyFor(patientId);
        redis.opsForValue().set(key, "[]");

        await().atMost(Duration.ofSeconds(15)).until(() -> !redis.hasKey(key));
    }

    @Test
    void replacingTheDefaultsDeletesTheirCachedCopyAndRecordsTheChange() throws Exception {
        thresholds.forPatient(patientId); // fills the cache, defaults included
        String key = ThresholdCache.keyFor(null);
        assertThat(redis.hasKey(key)).isTrue();
        List<ThresholdLimit> current =
                thresholds.load(null).stream().map(ThresholdLimit::of).toList();

        // Sent unchanged, so other tests still see the seeded defaults.
        put("/v1/admin/thresholds/defaults", json.writeValueAsString(new ThresholdSet(current)), ADMIN_KEY)
                .andExpect(status().isOk());

        assertThat(redis.hasKey(key)).isFalse();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM outbox
                        WHERE event_type = 'ThresholdsChanged' AND payload->>'patientId' IS NULL
                          AND created_at > now() - interval '1 minute'
                        """, Integer.class)).isPositive();
    }

    private ResultActions putPatientLimits(String body, String adminKey) throws Exception {
        return put("/v1/admin/thresholds/patients/" + patientId, body, adminKey);
    }

    private ResultActions put(String url, String body, String adminKey) throws Exception {
        var request = MockMvcRequestBuilders.put(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (adminKey != null) {
            request.header(AdminApiKeyFilter.HEADER, adminKey);
        }
        return mvc.perform(request);
    }

    private UUID sendReading(int systolic) throws Exception {
        UUID readingId = UUID.randomUUID();
        String body = """
                {"readings": [{"readingId": "%s", "metricType": "BLOOD_PRESSURE", "systolic": %d, "diastolic": 80,
                  "measuredAt": "%s"}]}
                """.formatted(readingId, systolic, Instant.now().minus(1, ChronoUnit.MINUTES));
        mvc.perform(MockMvcRequestBuilders.post("/v1/readings")
                        .header("X-Device-Key", deviceKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        return readingId;
    }

    private List<String> breachReason(UUID readingId) {
        return jdbc.queryForList("""
                SELECT payload->>'reason' FROM outbox
                WHERE event_type = 'ReadingBreached' AND payload->>'readingId' = ?
                """, String.class, readingId.toString());
    }
}
