package com.github.serbentd.eve.poller.client;

import com.evepipeline.esi.api.MarketApi;
import com.evepipeline.esi.api.UniverseApi;
import com.evepipeline.esi.model.MarketsRegionIdOrdersGetInner;
import com.github.serbentd.eve.poller.config.PollerProperties;
import com.github.serbentd.eve.poller.poller.MarketOrderPoller;
import com.github.serbentd.eve.poller.service.OrderEventPublisher;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@MockitoBean(types = {MarketApi.class, UniverseApi.class, OrderEventPublisher.class})
class EsiResilienceIntegrationTest {

    private static final Long THE_FORGE_REGION_ID = 10000002L;
    private static final Long JITA_SYSTEM_ID = 30000142L;
    private static final Long JITA_4_4_STATION_ID = 60003760L;
    private static final String LIVENESS_PATH = "/actuator/health/liveness";
    private static final String READINESS_PATH = "/actuator/health/readiness";
    private static final String HEALTH_PATH = "/actuator/health";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String VALID_API_KEY = "test-api-key";
    private static final String STATUS_UP = "UP";

    private final MarketOrderPoller marketOrderPoller;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final RetryRegistry retryRegistry;
    private final MarketApi marketApi;
    private final UniverseApi universeApi;
    private final OrderEventPublisher orderEventPublisher;
    private final WebTestClient webTestClient;
    private final PollerProperties properties;

    EsiResilienceIntegrationTest(
            MarketOrderPoller marketOrderPoller,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RateLimiterRegistry rateLimiterRegistry,
            RetryRegistry retryRegistry,
            MarketApi marketApi,
            UniverseApi universeApi,
            OrderEventPublisher orderEventPublisher,
            WebTestClient webTestClient,
            PollerProperties properties
    ) {
        this.marketOrderPoller = marketOrderPoller;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.retryRegistry = retryRegistry;
        this.marketApi = marketApi;
        this.universeApi = universeApi;
        this.orderEventPublisher = orderEventPublisher;
        this.webTestClient = webTestClient;
        this.properties = properties;
    }

    @BeforeEach
    @AfterEach
    void resetResilienceAndMocks() {
        circuitBreakerRegistry.circuitBreaker(properties.esi().resilienceInstance()).reset();
        Mockito.reset(marketApi, universeApi, orderEventPublisher);
    }

    @Test
    void resilienceBeansConfiguredFromYaml_shouldBindPredicatesAndParameters() {
        String instanceName = properties.esi().resilienceInstance();
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(instanceName);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(instanceName);
        Retry retry = retryRegistry.retry(instanceName);

        assertThat(rateLimiter.getRateLimiterConfig().getLimitForPeriod()).isEqualTo(100);
        assertThat(circuitBreaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(4);
        assertThat(circuitBreaker.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(4);
        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);
    }

    @Test
    void marketOrderPoller_whenTransient503Occurs_shouldRetryViaSpringResilienceAndSucceed() {
        MarketsRegionIdOrdersGetInner order = new MarketsRegionIdOrdersGetInner()
                .orderId(1001L)
                .typeId(34L)
                .systemId(JITA_SYSTEM_ID)
                .locationId(JITA_4_4_STATION_ID)
                .price(15.5)
                .volumeRemain(50L)
                .volumeTotal(100L)
                .minVolume(1L)
                .duration(90L)
                .isBuyOrder(false)
                .range(MarketsRegionIdOrdersGetInner.RangeEnum.REGION)
                .issued(OffsetDateTime.now());

        ResponseEntity<List<MarketsRegionIdOrdersGetInner>> okResponse = ResponseEntity.ok()
                .header("X-Pages", "1")
                .body(List.of(order));

        WebClientResponseException transient503 = WebClientResponseException.create(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Service Unavailable",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        );

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"),
                eq(THE_FORGE_REGION_ID),
                eq(properties.discovery().compatibilityDate()),
                eq(1),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        ))
                .thenReturn(Mono.error(transient503))
                .thenReturn(Mono.error(transient503))
                .thenReturn(Mono.just(okResponse));

        marketOrderPoller.pollRegion(THE_FORGE_REGION_ID);

        verify(marketApi, times(3)).getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"),
                eq(THE_FORGE_REGION_ID),
                eq(properties.discovery().compatibilityDate()),
                eq(1),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        );
        verify(orderEventPublisher, times(1)).publishOrderBatch(any());
        verify(orderEventPublisher, times(1)).publishSnapshotCompleted(any());
    }

    @Test
    void marketOrderPoller_whenCircuitOpens_shouldShortCircuitAndKeepHealthProbesUp() {
        WebClientResponseException errorLimited420 = WebClientResponseException.create(
                420,
                "Error Limited",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        );

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"),
                eq(THE_FORGE_REGION_ID),
                eq(properties.discovery().compatibilityDate()),
                eq(1),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        )).thenReturn(Mono.error(errorLimited420));

        // 4 calls reach minimum-number-of-calls=4 and open the circuit without retrying 420
        for (int i = 0; i < 4; i++) {
            marketOrderPoller.pollAllRegions();
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(properties.esi().resilienceInstance());
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        clearInvocations(marketApi);
        marketOrderPoller.pollAllRegions();

        verify(marketApi, never()).getMarketsRegionIdOrdersWithHttpInfo(
                any(), any(), any(), any(), any(), any(), any(), any(), any());

        webTestClient.get().uri(LIVENESS_PATH)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo(STATUS_UP);

        webTestClient.get().uri(READINESS_PATH)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo(STATUS_UP);

        webTestClient.get().uri(HEALTH_PATH)
                .header(API_KEY_HEADER, VALID_API_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo(STATUS_UP)
                .jsonPath("$.components.circuitBreakers.details.esi.details.state").isEqualTo("OPEN")
                .jsonPath("$.components.rateLimiters.details.esi.status").isEqualTo(STATUS_UP);
    }
}
