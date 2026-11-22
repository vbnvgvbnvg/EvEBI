package com.github.serbentd.eve.poller.client;

import com.github.serbentd.eve.poller.config.PollerProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EsiResilienceClientTest {

    private static final String INSTANCE_NAME = "esi";
    private static final Long THE_FORGE_REGION_ID = 10000002L;

    @Test
    void execute_whenTransient503Occurs_shouldRetryAndSucceed() {
        CircuitBreakerRegistry cbRegistry = defaultCircuitBreakerRegistry(10);
        EsiResilienceClient client = createClient(RateLimiterRegistry.ofDefaults(), cbRegistry, defaultRetryRegistry(3));

        AtomicInteger attempts = new AtomicInteger(0);
        Mono<String> result = client.execute(() -> Mono.defer(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                return Mono.error(httpException(503, "Service Unavailable"));
            }
            return Mono.just("ok");
        }));

        StepVerifier.create(result)
                .expectNext("ok")
                .verifyComplete();

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(cbRegistry.circuitBreaker(INSTANCE_NAME).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void execute_when404Occurs_shouldNotRetryAndNotTripCircuitBreaker() {
        CircuitBreakerRegistry cbRegistry = defaultCircuitBreakerRegistry(2);
        EsiResilienceClient client = createClient(RateLimiterRegistry.ofDefaults(), cbRegistry, defaultRetryRegistry(3));

        AtomicInteger attempts = new AtomicInteger(0);
        Mono<String> result = client.execute(() -> {
            attempts.incrementAndGet();
            return Mono.error(httpException(404, "Not Found"));
        });

        StepVerifier.create(result)
                .expectError(WebClientResponseException.class)
                .verify();

        CircuitBreaker cb = cbRegistry.circuitBreaker(INSTANCE_NAME);
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void execute_when420ErrorLimitedOccurs_shouldNotRetryButRecordCircuitBreakerFailure() {
        CircuitBreakerRegistry cbRegistry = defaultCircuitBreakerRegistry(2);
        EsiResilienceClient client = createClient(RateLimiterRegistry.ofDefaults(), cbRegistry, defaultRetryRegistry(3));

        AtomicInteger attempts = new AtomicInteger(0);
        for (int i = 0; i < 2; i++) {
            StepVerifier.create(client.execute(() -> {
                        attempts.incrementAndGet();
                        return Mono.error(httpException(420, "Error Limited"));
                    }))
                    .expectError(WebClientResponseException.class)
                    .verify();
        }

        CircuitBreaker cb = cbRegistry.circuitBreaker(INSTANCE_NAME);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void execute_whenCircuitOpen_shouldRejectImmediatelyWithoutInvokingSupplierOrRetrying() {
        CircuitBreakerRegistry cbRegistry = defaultCircuitBreakerRegistry(2);
        EsiResilienceClient client = createClient(RateLimiterRegistry.ofDefaults(), cbRegistry, defaultRetryRegistry(3));
        cbRegistry.circuitBreaker(INSTANCE_NAME).transitionToOpenState();

        AtomicInteger attempts = new AtomicInteger(0);
        StepVerifier.create(client.execute(() -> {
                    attempts.incrementAndGet();
                    return Mono.just("should-not-run");
                }))
                .expectError(CallNotPermittedException.class)
                .verify();

        assertThat(attempts.get()).isZero();
    }

    @Test
    void execute_whenRateLimitExceeded_shouldFailWithRequestNotPermittedWithoutTrippingCircuit() {
        RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build());
        CircuitBreakerRegistry cbRegistry = defaultCircuitBreakerRegistry(2);
        EsiResilienceClient client = createClient(rateLimiterRegistry, cbRegistry, defaultRetryRegistry(3));

        StepVerifier.create(client.execute(() -> Mono.just("first")))
                .expectNext("first")
                .verifyComplete();

        StepVerifier.create(client.execute(() -> Mono.just("second")))
                .expectError(RequestNotPermitted.class)
                .verify();

        CircuitBreaker cb = cbRegistry.circuitBreaker(INSTANCE_NAME);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void execute_whenSupplierIsNull_shouldThrowNullPointerExceptionImmediately() {
        EsiResilienceClient client = createClient(
                RateLimiterRegistry.ofDefaults(),
                defaultCircuitBreakerRegistry(2),
                defaultRetryRegistry(3)
        );

        assertThatThrownBy(() -> client.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ESI call supplier must not be null");
    }

    private EsiResilienceClient createClient(
            RateLimiterRegistry rateLimiterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry
    ) {
        return new EsiResilienceClient(rateLimiterRegistry, circuitBreakerRegistry, retryRegistry, createProperties());
    }

    private static CircuitBreakerRegistry defaultCircuitBreakerRegistry(int slidingWindowSize) {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(slidingWindowSize)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordException(new EsiCircuitBreakerFailurePredicate())
                .ignoreExceptions(RequestNotPermitted.class)
                .build());
    }

    private static RetryRegistry defaultRetryRegistry(int maxAttempts) {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(1))
                .retryOnException(new EsiRetryExceptionPredicate())
                .ignoreExceptions(CallNotPermittedException.class, RequestNotPermitted.class)
                .build());
    }

    private static WebClientResponseException httpException(int status, String reason) {
        return WebClientResponseException.create(status, reason, HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    private static PollerProperties createProperties() {
        return new PollerProperties(
                new PollerProperties.EsiProperties(
                        "https://esi.evetech.net",
                        "EvEBI-Test/1.0",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        DataSize.ofMegabytes(1),
                        20,
                        INSTANCE_NAME
                ),
                new PollerProperties.SchedulingProperties(
                        List.of(THE_FORGE_REGION_ID),
                        Duration.ofHours(1),
                        Duration.ofHours(1)
                ),
                new PollerProperties.AmqpProperties("test.market.orders", "test.market.orders.raw"),
                new PollerProperties.DiscoveryProperties(
                        false,
                        10000000L,
                        11000000L,
                        Duration.ofHours(24),
                        LocalDate.of(2020, 1, 1)
                )
        );
    }
}
