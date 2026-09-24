package com.github.serbentd.eve.poller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class IngestionPollerApplicationTest {

    private static final String LIVENESS_PATH = "/actuator/health/liveness";
    private static final String READINESS_PATH = "/actuator/health/readiness";
    private static final String STATUS_UP = "UP";

    private final WebTestClient webTestClient;

    IngestionPollerApplicationTest(WebTestClient webTestClient) {
        this.webTestClient = webTestClient;
    }

    @Test
    void contextLoads_andLivenessProbeReturnsUp() {
        webTestClient.get()
                .uri(LIVENESS_PATH)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo(STATUS_UP);
    }

    @Test
    void readinessProbeReturnsUp() {
        webTestClient.get()
                .uri(READINESS_PATH)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo(STATUS_UP);
    }
}
