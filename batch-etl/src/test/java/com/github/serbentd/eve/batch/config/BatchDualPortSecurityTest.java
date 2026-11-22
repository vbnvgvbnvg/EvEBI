package com.github.serbentd.eve.batch.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "spring.datasource.url=jdbc:h2:mem:batch_dual_port_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
        }
)
@AutoConfigureRestTestClient
class BatchDualPortSecurityTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String ADMIN_KEY = "test-api-key";
    private static final String ACTUATOR_KEY = "test-actuator-key";
    private static final String OPERATOR_KEY = "test-operator-key";

    private final RestTestClient restTestClient;
    private final int serverPort;
    private final int managementPort;

    BatchDualPortSecurityTest(
            RestTestClient restTestClient,
            @LocalServerPort int serverPort,
            @LocalManagementPort int managementPort) {
        this.restTestClient = restTestClient;
        this.serverPort = serverPort;
        this.managementPort = managementPort;
    }

    @Test
    @DisplayName("Management port should be isolated from main application port and preserve 403/404 status codes")
    void dualPortIsolation_shouldEnforcePortSeparationAndPreserveErrorStatuses() {
        assertThat(managementPort).isNotEqualTo(serverPort);

        // Unauthenticated probes succeed on management port without exposing component details
        String livenessBody = restTestClient.get()
                .uri(url(managementPort, "/actuator/health/liveness"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(livenessBody).contains("\"UP\"").doesNotContain("components");

        // Actuator routes are not exposed on main application port
        restTestClient.get()
                .uri(url(serverPort, "/actuator/health/liveness"))
                .header(API_KEY_HEADER, ADMIN_KEY)
                .exchange()
                .expectStatus().isNotFound();

        // Batch API routes are not exposed on management port
        restTestClient.get()
                .uri(url(managementPort, "/api/v1/jobs/executions/9999999"))
                .header(API_KEY_HEADER, ADMIN_KEY)
                .exchange()
                .expectStatus().isNotFound();

        // Scoped keys receive 403 Forbidden (not 401) across real Tomcat error dispatch
        restTestClient.post()
                .uri(url(serverPort, "/api/v1/jobs/daily-summary"))
                .header(API_KEY_HEADER, ACTUATOR_KEY)
                .exchange()
                .expectStatus().isForbidden();

        restTestClient.get()
                .uri(url(managementPort, "/actuator/health"))
                .header(API_KEY_HEADER, OPERATOR_KEY)
                .exchange()
                .expectStatus().isForbidden();

        // Direct external access to /error remains denied
        restTestClient.get()
                .uri(url(serverPort, "/error"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static String url(int port, String path) {
        return "http://localhost:" + port + path;
    }
}
