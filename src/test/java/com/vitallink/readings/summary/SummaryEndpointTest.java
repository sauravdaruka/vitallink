package com.vitallink.readings.summary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vitallink.readings.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SummaryEndpointTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void aSummaryAlwaysSaysWhoWroteItAndCarriesTheFacts() throws Exception {
        mvc.perform(get("/v1/patients/patient-with-no-readings/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.facts.readingCount").value(0))
                .andExpect(jsonPath("$.days").value(7));
    }

    @Test
    void daysOutsideOneToThirtyAreRejected() throws Exception {
        mvc.perform(get("/v1/patients/p/summary?days=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/patients/p/summary?days=31")).andExpect(status().isBadRequest());
    }
}
