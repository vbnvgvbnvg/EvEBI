package com.github.serbentd.eve.poller.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class PollerSecurityIntegrationTest {

    private static final String LIVENESS_PATH = "/actuator/health/liveness";
    private static final String READINESS_PATH = "/actuator/health/readiness";
    private static final String HEALTH_PATH = "/actuator/health";
    private static final String INFO_PATH = "/actuator/info";
    private static final String ENV_PATH = "/actuator/env";
    private static final String BEANS_PATH = "/actuator/beans";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String VALID_API_KEY = "test-api-key";
    private static final String ROTATED_ACTUATOR_API_KEY = "test-actuator-key-rotated";
    private static final String INVALID_API_KEY = "wrong-api-key";
    private static final String VALID_USER = "test-admin";
    private static final String VALID_PASSWORD = "test-password";

    private final WebTestClient webTestClient;

    PollerSecurityIntegrationTest(WebTestClient webTestClient) {
        this.webTestClient = webTestClient;
    }

    @Test
    @DisplayName("Health probes should be accessible without authentication and hide details")
    void healthProbes_whenUnauthenticated_shouldReturnOkWithoutDetails() {
        webTestClient.get().uri(LIVENESS_PATH)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();

        webTestClient.get().uri(READINESS_PATH)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
    }

    @Test
    @DisplayName("Protected actuator endpoints should return 401 when unauthenticated")
    void actuatorEndpoints_whenUnauthenticated_shouldReturn401() {
        webTestClient.get().uri(HEALTH_PATH).exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri(INFO_PATH).exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri(ENV_PATH).exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri(BEANS_PATH).exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Invalid API key or Basic auth should return 401")
    void actuatorEndpoints_withInvalidCredentials_shouldReturn401() {
        webTestClient.get().uri(HEALTH_PATH)
                .header(API_KEY_HEADER, INVALID_API_KEY)
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get().uri(HEALTH_PATH)
                .header(API_KEY_HEADER, "   ")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get().uri(HEALTH_PATH)
                .header("Authorization", basicAuth(VALID_USER, "wrong-password"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Valid API key should grant access to health and info endpoints")
    void actuatorEndpoints_withValidApiKey_shouldReturnOk() {
        webTestClient.get().uri(HEALTH_PATH)
                .header(API_KEY_HEADER, VALID_API_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");

        webTestClient.get().uri(INFO_PATH)
                .header(API_KEY_HEADER, VALID_API_KEY)
                .exchange()
                .expectStatus().isOk();

        webTestClient.get().uri(HEALTH_PATH)
                .header(API_KEY_HEADER, ROTATED_ACTUATOR_API_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("Valid HTTP Basic credentials should grant access to actuator endpoints")
    void actuatorEndpoints_withValidBasicAuth_shouldReturnOk() {
        webTestClient.get().uri(HEALTH_PATH)
                .header("Authorization", basicAuth(VALID_USER, VALID_PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("Authenticated user without ACTUATOR or ADMIN role should receive 403 Forbidden")
    void actuatorEndpoints_withInsufficientRole_shouldReturn403() {
        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockUser("limited-user").roles("USER"))
                .get().uri(HEALTH_PATH)
                .exchange()
                .expectStatus().isForbidden();
    }

    private static String basicAuth(String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }
}
