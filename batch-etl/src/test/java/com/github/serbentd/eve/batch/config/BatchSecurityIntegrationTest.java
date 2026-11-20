package com.github.serbentd.eve.batch.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:batch_security_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@AutoConfigureMockMvc
class BatchSecurityIntegrationTest {

    private static final String LIVENESS_PATH = "/actuator/health/liveness";
    private static final String READINESS_PATH = "/actuator/health/readiness";
    private static final String HEALTH_PATH = "/actuator/health";
    private static final String INFO_PATH = "/actuator/info";
    private static final String ENV_PATH = "/actuator/env";
    private static final String JOBS_EXECUTION_PATH = "/api/v1/jobs/executions/9999999";
    private static final String JOBS_DAILY_SUMMARY_PATH = "/api/v1/jobs/daily-summary";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String VALID_API_KEY = "test-api-key";
    private static final String ROTATED_ADMIN_API_KEY = "test-admin-key-rotated";
    private static final String ACTUATOR_API_KEY = "test-actuator-key";
    private static final String ROTATED_ACTUATOR_API_KEY = "test-actuator-key-rotated";
    private static final String OPERATOR_API_KEY = "test-operator-key";
    private static final String ROTATED_OPERATOR_API_KEY = "test-operator-key-rotated";
    private static final String INVALID_API_KEY = "wrong-api-key";
    private static final String VALID_USER = "test-admin";
    private static final String VALID_PASSWORD = "test-password";

    private final MockMvc mockMvc;

    BatchSecurityIntegrationTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    @DisplayName("Health probes should be accessible without authentication and hide component details")
    void healthProbes_whenUnauthenticated_shouldReturnOkWithoutDetails() throws Exception {
        mockMvc.perform(get(LIVENESS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());

        mockMvc.perform(get(READINESS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("Protected actuator and batch API endpoints should return 401 when unauthenticated")
    void protectedEndpoints_whenUnauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(get(HEALTH_PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(INFO_PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ENV_PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(JOBS_EXECUTION_PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(JOBS_DAILY_SUMMARY_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Invalid API key or HTTP Basic credentials should return 401")
    void protectedEndpoints_withInvalidCredentials_shouldReturn401() throws Exception {
        mockMvc.perform(get(JOBS_EXECUTION_PATH).header(API_KEY_HEADER, INVALID_API_KEY))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(JOBS_EXECUTION_PATH).header(API_KEY_HEADER, "   "))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(HEALTH_PATH).with(httpBasic(VALID_USER, "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Valid primary and rotated admin API keys should grant access to actuator and batch API endpoints")
    void protectedEndpoints_withValidAdminApiKeys_shouldSucceed() throws Exception {
        for (String adminKey : new String[]{VALID_API_KEY, ROTATED_ADMIN_API_KEY}) {
            mockMvc.perform(get(HEALTH_PATH).header(API_KEY_HEADER, adminKey))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components.db.status").value("UP"));

            mockMvc.perform(get(JOBS_EXECUTION_PATH).header(API_KEY_HEADER, adminKey))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("Scoped actuator and operator API keys (including rotated keys) should enforce least-privilege access")
    void protectedEndpoints_withScopedApiKeys_shouldEnforceLeastPrivilege() throws Exception {
        for (String actuatorKey : new String[]{ACTUATOR_API_KEY, ROTATED_ACTUATOR_API_KEY}) {
            mockMvc.perform(get(HEALTH_PATH).header(API_KEY_HEADER, actuatorKey))
                    .andExpect(status().isOk());
            mockMvc.perform(get(JOBS_EXECUTION_PATH).header(API_KEY_HEADER, actuatorKey))
                    .andExpect(status().isForbidden());
        }

        for (String operatorKey : new String[]{OPERATOR_API_KEY, ROTATED_OPERATOR_API_KEY}) {
            mockMvc.perform(get(JOBS_EXECUTION_PATH).header(API_KEY_HEADER, operatorKey))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get(HEALTH_PATH).header(API_KEY_HEADER, operatorKey))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("Valid HTTP Basic credentials should grant access to actuator and batch API endpoints")
    void protectedEndpoints_withValidBasicAuth_shouldSucceed() throws Exception {
        mockMvc.perform(get(HEALTH_PATH).with(httpBasic(VALID_USER, VALID_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get(JOBS_EXECUTION_PATH).with(httpBasic(VALID_USER, VALID_PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ACTUATOR")
    @DisplayName("ACTUATOR role can access actuator endpoints but is forbidden from batch API endpoints")
    void actuatorRole_shouldAccessActuatorAndBeForbiddenFromBatchApi() throws Exception {
        mockMvc.perform(get(HEALTH_PATH))
                .andExpect(status().isOk());

        mockMvc.perform(get(JOBS_EXECUTION_PATH))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "BATCH_OPERATOR")
    @DisplayName("BATCH_OPERATOR role can access batch API endpoints but is forbidden from actuator endpoints")
    void batchOperatorRole_shouldAccessBatchApiAndBeForbiddenFromActuator() throws Exception {
        mockMvc.perform(get(JOBS_EXECUTION_PATH))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(HEALTH_PATH))
                .andExpect(status().isForbidden());
    }
}
