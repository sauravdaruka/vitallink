package com.vitallink.readings.query;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vitallink.readings.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Each test has its own patient and coaches, so other tests' data can't interfere. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class QueryTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String suffix;
    private String patient;
    private final Instant newest = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void aPatientWithFiveReadingsAMinuteApart() {
        suffix = UUID.randomUUID().toString();
        patient = "patient-" + suffix;
        for (int i = 0; i < 5; i++) {
            jdbc.update("""
                    INSERT INTO readings (device_id, reading_id, patient_id, metric_type, systolic, diastolic, measured_at)
                    VALUES ('bp-test', ?, ?, 'BLOOD_PRESSURE', ?, 80, ?)
                    """, UUID.randomUUID(), patient, 120 + i, Timestamp.from(newest.minus(i, ChronoUnit.MINUTES)));
        }
    }

    @Test
    void latestReturnsTheNewestReadingsFirstUpToTheLimit() throws Exception {
        mvc.perform(get("/v1/patients/{id}/readings/latest?limit=3", patient))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readings[*].systolic").value(Matchers.contains(120, 121, 122)));
    }

    @Test
    void aTimeRangeReturnsOnlyReadingsInsideIt() throws Exception {
        mvc.perform(get("/v1/patients/{id}/readings", patient)
                        .param("from", newest.minus(3, ChronoUnit.MINUTES).toString())
                        .param("to", newest.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readings[*].systolic").value(Matchers.contains(121, 122, 123)));
    }

    @Test
    void invalidRangesAreRejected() throws Exception {
        mvc.perform(get("/v1/patients/{id}/readings", patient)
                        .param("from", newest.toString())
                        .param("to", newest.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/patients/{id}/readings", patient)
                        .param("from", newest.minus(91, ChronoUnit.DAYS).toString())
                        .param("to", newest.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/patients/{id}/readings", patient).param("to", newest.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCoachsQueueHasOnlyTheirAlertsNewestFirst() throws Exception {
        String asha = "asha-" + suffix;
        alert(asha, "older", 2);
        alert(asha, "newer", 1);
        alert("ben-" + suffix, "someone else's", 1);

        mvc.perform(get("/v1/coaches/{id}/alerts", asha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[*].reason").value(Matchers.contains("newer", "older")));
    }

    private void alert(String coachId, String reason, int hoursAgo) {
        jdbc.update("""
                INSERT INTO alerts (device_id, reading_id, measured_at, patient_id, systolic, diastolic, reason, coach_id,
                                    created_at)
                VALUES ('bp-test', ?, now(), ?, 195, 95, ?, ?, now() - make_interval(hours => ?))
                """, UUID.randomUUID(), patient, reason, coachId, hoursAgo);
    }
}
