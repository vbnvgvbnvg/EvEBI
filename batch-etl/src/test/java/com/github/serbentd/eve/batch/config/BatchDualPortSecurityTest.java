package com.github.serbentd.eve.batch.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "spring.datasource.url=jdbc:h2:mem:batch_dual_port_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
        }
)
class BatchDualPortSecurityTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String ADMIN_KEY = "test-api-key";
    private static final String ACTUATOR_KEY = "test-actuator-key";
    private static final String OPERATOR_KEY = "test-operator-key";

    private final TestRestTemplate restTemplate;
    private final int serverPort;
    private final int managementPort;

    BatchDualPortSecurityTest(
            TestRestTemplate restTemplate,
            @LocalServerPort int serverPort,
            @LocalManagementPort int managementPort) {
        this.restTemplate = restTemplate;
        this.serverPort = serverPort;
        this.managementPort = managementPort;
    }

    @Test
    @DisplayName("Management port should be isolated from main application port and preserve 403/404 status codes")
    void dualPortIsolation_shouldEnforcePortSeparationAndPreserveErrorStatuses() {
        assertThat(managementPort).isNotEqualTo(serverPort);

        // Unauthenticated probes succeed on management port without exposing component details
        ResponseEntity<String> liveness = restTemplate.getForEntity(
                url(managementPort, "/actuator/health/liveness"), String.class);
        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveness.getBody()).contains("\"UP\"").doesNotContain("components");

        // Actuator routes are not exposed on main application port
        ResponseEntity<String> appActuator = restTemplate.exchange(
                url(serverPort, "/actuator/health/liveness"), HttpMethod.GET,
                withApiKey(ADMIN_KEY), String.class);
        assertThat(appActuator.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Batch API routes are not exposed on management port
        ResponseEntity<String> mgmtJobs = restTemplate.exchange(
                url(managementPort, "/api/v1/jobs/executions/9999999"), HttpMethod.GET,
                withApiKey(ADMIN_KEY), String.class);
        assertThat(mgmtJobs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Scoped keys receive 403 Forbidden (not 401) across real Tomcat error dispatch
        ResponseEntity<String> actuatorOnJobs = restTemplate.exchange(
                url(serverPort, "/api/v1/jobs/daily-summary"), HttpMethod.POST,
                withApiKey(ACTUATOR_KEY), String.class);
        assertThat(actuatorOnJobs.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> operatorOnActuator = restTemplate.exchange(
                url(managementPort, "/actuator/health"), HttpMethod.GET,
                withApiKey(OPERATOR_KEY), String.class);
        assertThat(operatorOnActuator.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Direct external access to /error remains denied
        ResponseEntity<String> directError = restTemplate.getForEntity(
                url(serverPort, "/error"), String.class);
        assertThat(directError.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static HttpEntity<Void> withApiKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(API_KEY_HEADER, key);
        return new HttpEntity<>(headers);
    }

    private static String url(int port, String path) {
        return "http://localhost:" + port + path;
    }
}
