package com.vitallink.readings.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vitallink.readings.TestcontainersConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Uses the seeded device bp-001, which belongs to patient-001. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class IngestEndpointTest {

    private static final String KEY = "local-key-bp-001";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aSingleReadingIsAcceptedAndStoredForTheDevicesPatient() throws Exception {
        UUID readingId = UUID.randomUUID();

        send(batch(null, reading(readingId, 120, 80)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].readingId").value(readingId.toString()))
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"));

        assertThat(storedPatientOf(readingId)).isEqualTo("patient-001");
    }

    @Test
    void sendingTheSameReadingAgainIsADuplicate() throws Exception {
        String body = batch(null, reading(UUID.randomUUID(), 120, 80));

        send(body).andExpect(jsonPath("$.results[0].status").value("ACCEPTED"));
        send(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("DUPLICATE"));
    }

    @Test
    void eachReadingInABatchGetsItsOwnResultInTheSameOrder() throws Exception {
        UUID valid = UUID.randomUUID();
        UUID invalid = UUID.randomUUID();

        send(batch(null, reading(valid, 120, 80), reading(invalid, 80, 120), reading(valid, 120, 80)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[1].status").value("REJECTED"))
                .andExpect(jsonPath("$.results[1].reason").value("systolic must be higher than diastolic"))
                .andExpect(jsonPath("$.results[2].status").value("DUPLICATE"));

        assertThat(storedPatientOf(invalid)).isNull();
    }

    @Test
    void theRightPatientIdIsAccepted() throws Exception {
        send(batch("patient-001", reading(UUID.randomUUID(), 120, 80)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"));
    }

    @Test
    void aPatientIdThatDoesNotMatchTheDeviceIsForbiddenAndNothingIsStored() throws Exception {
        UUID readingId = UUID.randomUUID();

        send(batch("patient-002", reading(readingId, 120, 80))).andExpect(status().isForbidden());

        assertThat(storedPatientOf(readingId)).isNull();
    }

    @Test
    void aMismatchIsForbiddenEvenWhenTheBatchIsAlsoInvalid() throws Exception {
        send("{\"patientId\": \"patient-002\", \"readings\": []}").andExpect(status().isForbidden());
    }

    @Test
    void anEmptyBatchIsABadRequest() throws Exception {
        send("{\"readings\": []}").andExpect(status().isBadRequest());
    }

    @Test
    void aBatchOverTheLimitIsTooLarge() throws Exception {
        String[] readings =
                Collections.nCopies(501, reading(UUID.randomUUID(), 120, 80)).toArray(String[]::new);

        send(batch(null, readings)).andExpect(status().isContentTooLarge());
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/v1/readings")
                .header("X-Device-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String batch(String patientId, String... readings) {
        String patient = patientId == null ? "" : "\"patientId\": \"%s\",".formatted(patientId);
        return "{%s \"readings\": [%s]}".formatted(patient, String.join(",", readings));
    }

    private static String reading(UUID readingId, int systolic, int diastolic) {
        Instant measuredAt = Instant.now().minus(1, ChronoUnit.HOURS);
        return """
                {"readingId": "%s", "metricType": "BLOOD_PRESSURE", "systolic": %d, "diastolic": %d, "pulse": 72,
                 "measuredAt": "%s"}
                """.formatted(readingId, systolic, diastolic, measuredAt);
    }

    private String storedPatientOf(UUID readingId) {
        return jdbc
                .queryForList("SELECT patient_id FROM readings WHERE reading_id = ?", String.class, readingId)
                .stream()
                .findFirst()
                .orElse(null);
    }
}
