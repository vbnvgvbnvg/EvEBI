package com.github.serbentd.eve.worker.config;

import com.github.serbentd.eve.common.security.EveSecurityProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:worker_security_test;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@AutoConfigureMockMvc
class WorkerSecurityIntegrationTest {

    private static final String LIVENESS_PATH = "/actuator/health/liveness";
    private static final String READINESS_PATH = "/actuator/health/readiness";
    private static final String HEALTH_PATH = "/actuator/health";
    private static final String INFO_PATH = "/actuator/info";
    private static final String ENV_PATH = "/actuator/env";
    private static final String BEANS_PATH = "/actuator/beans";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String VALID_API_KEY = "test-api-key";
    private static final String ROTATED_ACTUATOR_KEY = "test-actuator-key-rotated";
    private static final String INVALID_API_KEY = "wrong-api-key";
    private static final String VALID_USER = "test-admin";
    private static final String VALID_PASSWORD = "test-password";

    private final MockMvc mockMvc;

    WorkerSecurityIntegrationTest(MockMvc mockMvc) {
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
    @DisplayName("Protected actuator endpoints should return 401 when unauthenticated")
    void actuatorEndpoints_whenUnauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(get(HEALTH_PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(INFO_PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ENV_PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BEANS_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Invalid API key or HTTP Basic credentials should return 401")
    void actuatorEndpoints_withInvalidCredentials_shouldReturn401() throws Exception {
        mockMvc.perform(get(HEALTH_PATH).header(API_KEY_HEADER, INVALID_API_KEY))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(HEALTH_PATH).header(API_KEY_HEADER, "   "))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(HEALTH_PATH).with(httpBasic(VALID_USER, "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Valid API key should grant access to actuator endpoints and expose health details")
    void actuatorEndpoints_withValidApiKey_shouldReturnOk() throws Exception {
        mockMvc.perform(get(HEALTH_PATH).header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));

        mockMvc.perform(get(INFO_PATH).header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk());

        mockMvc.perform(get(HEALTH_PATH).header(API_KEY_HEADER, ROTATED_ACTUATOR_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Valid HTTP Basic credentials should grant access to actuator endpoints")
    void actuatorEndpoints_withValidBasicAuth_shouldReturnOk() throws Exception {
        mockMvc.perform(get(HEALTH_PATH).with(httpBasic(VALID_USER, VALID_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Authenticated user without ACTUATOR or ADMIN role should receive 403 Forbidden")
    void actuatorEndpoints_withInsufficientRole_shouldReturn403() throws Exception {
        mockMvc.perform(get(HEALTH_PATH))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("EveSecurityProperties should enforce NotBlank and NotEmpty validation constraints")
    void eveSecurityProperties_whenBlank_shouldFailValidation() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            EveSecurityProperties valid = new EveSecurityProperties(
                    VALID_USER, VALID_PASSWORD, API_KEY_HEADER,
                    java.util.List.of(VALID_API_KEY), java.util.List.of(ROTATED_ACTUATOR_KEY), java.util.List.of()
            );
            assertThat(validator.validate(valid)).isEmpty();

            EveSecurityProperties invalid = new EveSecurityProperties(
                    "", "   ", "", java.util.List.of(), java.util.List.of(), null
            );
            Set<String> violatedPaths = validator.validate(invalid).stream()
                    .map(v -> v.getPropertyPath().toString())
                    .collect(Collectors.toSet());
            assertThat(violatedPaths).contains("username", "password", "apiKeyHeader", "adminApiKeys", "actuatorApiKeys");
        }
    }
}
