package com.github.serbentd.eve.poller.client;

import com.github.serbentd.eve.poller.config.PollerProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reactive resilience facade wrapping outbound CCP ESI calls with Resilience4j
 * {@link RateLimiter}, {@link CircuitBreaker}, and {@link Retry} operators.
 * <p>
 * Operator ordering ensures retries re-acquire rate-limiter permits and respect
 * open circuit breakers without hammering an unavailable upstream API.
 */
@Slf4j
@Component
public class EsiResilienceClient {

    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public EsiResilienceClient(
            RateLimiterRegistry rateLimiterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            PollerProperties properties
    ) {
        String instanceName = properties.esi().resilienceInstance();
        this.rateLimiter = rateLimiterRegistry.rateLimiter(instanceName);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(instanceName);
        this.retry = retryRegistry.retry(instanceName);

        this.circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("ESI circuit breaker '{}' transitioned: {}",
                        event.getCircuitBreakerName(), event.getStateTransition()));
        this.retry.getEventPublisher().onRetry(event ->
                log.warn("Retrying ESI call '{}' (attempt #{}): {}",
                        event.getName(),
                        event.getNumberOfRetryAttempts(),
                        event.getLastThrowable().getMessage()));
        this.rateLimiter.getEventPublisher().onFailure(event ->
                log.warn("ESI rate limiter '{}' rejected request: {}",
                        event.getRateLimiterName(), event.getEventType()));
    }

    /**
     * Executes a deferred reactive ESI call protected by rate limiting, circuit breaking, and retry.
     *
     * @param call supplier producing a fresh {@link Mono} per attempt
     * @param <T>  response payload type
     * @return resilient {@link Mono}
     */
    public <T> Mono<T> execute(Supplier<Mono<T>> call) {
        Objects.requireNonNull(call, "ESI call supplier must not be null");
        return Mono.defer(call)
                .transformDeferred(RateLimiterOperator.of(rateLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry));
    }
}
