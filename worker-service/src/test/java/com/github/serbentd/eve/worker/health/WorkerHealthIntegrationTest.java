package com.github.serbentd.eve.worker.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:worker_health_test;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@AutoConfigureMockMvc
class WorkerHealthIntegrationTest {

    private static final String HEALTH_PATH = "/actuator/health";
    private static final String LIVENESS_PATH = "/actuator/health/liveness";
    private static final String READINESS_PATH = "/actuator/health/readiness";
    private static final String STATUS_UP = "UP";

    private final MockMvc mockMvc;

    WorkerHealthIntegrationTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void livenessProbe_shouldReturnHttp200AndStatusUp() throws Exception {
        mockMvc.perform(get(LIVENESS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(STATUS_UP));
    }

    @Test
    void readinessProbe_shouldReturnHttp200AndStatusUpWithDatabase() throws Exception {
        mockMvc.perform(get(READINESS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(STATUS_UP))
                .andExpect(jsonPath("$.components.db.status").value(STATUS_UP));
    }

    @Test
    void overallHealth_shouldReturnHttp200AndStatusUp() throws Exception {
        mockMvc.perform(get(HEALTH_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(STATUS_UP));
    }
}
