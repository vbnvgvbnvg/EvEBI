package com.github.serbentd.eve.batch.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:batch_health_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@AutoConfigureMockMvc
class BatchHealthIntegrationTest {

    private static final String HEALTH_PATH = "/actuator/health";
    private static final String LIVENESS_PATH = "/actuator/health/liveness";
    private static final String READINESS_PATH = "/actuator/health/readiness";
    private static final String STATUS_UP = "UP";

    private final MockMvc mockMvc;

    BatchHealthIntegrationTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    @DisplayName("Liveness probe should return HTTP 200 and status UP")
    void livenessProbe_shouldReturnHttp200AndStatusUp() throws Exception {
        mockMvc.perform(get(LIVENESS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(STATUS_UP));
    }

    @Test
    @DisplayName("Readiness probe should return HTTP 200 and status UP with database")
    void readinessProbe_shouldReturnHttp200AndStatusUpWithDatabase() throws Exception {
        mockMvc.perform(get(READINESS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(STATUS_UP))
                .andExpect(jsonPath("$.components.db.status").value(STATUS_UP));
    }

    @Test
    @DisplayName("Overall health should return HTTP 200 and status UP")
    void overallHealth_shouldReturnHttp200AndStatusUp() throws Exception {
        mockMvc.perform(get(HEALTH_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(STATUS_UP));
    }
}
