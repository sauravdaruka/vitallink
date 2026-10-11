package com.vitallink.readings.device;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vitallink.readings.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Uses the seeded devices and keys from db/seed. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DeviceAuthenticationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void requestWithoutAKeyIsUnauthorized() throws Exception {
        mvc.perform(post("/v1/readings")).andExpect(status().isUnauthorized());
    }

    @Test
    void requestWithAnUnknownKeyIsUnauthorized() throws Exception {
        mvc.perform(post("/v1/readings").header(DeviceApiKeyFilter.HEADER, "not-a-real-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requestWithARevokedKeyIsUnauthorized() throws Exception {
        mvc.perform(post("/v1/readings").header(DeviceApiKeyFilter.HEADER, "local-key-bp-003-old"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requestWithAValidKeyGetsPastAuthentication() throws Exception {
        // No body, so the endpoint answers 400. Getting that far means the key was accepted.
        mvc.perform(post("/v1/readings").header(DeviceApiKeyFilter.HEADER, "local-key-bp-001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void endpointsWithoutTheirOwnSecurityRulesAreDenied() throws Exception {
        mvc.perform(get("/v1/anything-else")).andExpect(status().isForbidden());
    }

    @Test
    void healthCheckStaysOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
