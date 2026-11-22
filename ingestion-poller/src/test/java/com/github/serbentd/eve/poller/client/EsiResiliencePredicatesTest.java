package com.github.serbentd.eve.poller.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class EsiResiliencePredicatesTest {

    private static final URI TEST_URI = URI.create("https://esi.evetech.net/latest/markets/10000002/orders/");

    private final EsiCircuitBreakerFailurePredicate circuitBreakerPredicate = new EsiCircuitBreakerFailurePredicate();
    private final EsiRetryExceptionPredicate retryPredicate = new EsiRetryExceptionPredicate();

    @Test
    void circuitBreakerPredicate_shouldRecord5xx420429AndTransportErrors() {
        assertThat(circuitBreakerPredicate.test(httpResponseException(500, "Internal Server Error"))).isTrue();
        assertThat(circuitBreakerPredicate.test(httpResponseException(502, "Bad Gateway"))).isTrue();
        assertThat(circuitBreakerPredicate.test(httpResponseException(503, "Service Unavailable"))).isTrue();
        assertThat(circuitBreakerPredicate.test(httpResponseException(504, "Gateway Timeout"))).isTrue();
        assertThat(circuitBreakerPredicate.test(httpResponseException(420, "Error Limited"))).isTrue();
        assertThat(circuitBreakerPredicate.test(httpResponseException(429, "Too Many Requests"))).isTrue();
        assertThat(circuitBreakerPredicate.test(new RuntimeException("Wrapped 503", httpResponseException(503, "Service Unavailable")))).isTrue();
        assertThat(circuitBreakerPredicate.test(
                new WebClientRequestException(new IOException("Connection reset"), HttpMethod.GET, TEST_URI, HttpHeaders.EMPTY)
        )).isTrue();
        assertThat(circuitBreakerPredicate.test(new IOException("Socket closed"))).isTrue();
        assertThat(circuitBreakerPredicate.test(new TimeoutException("Read timed out"))).isTrue();
        assertThat(circuitBreakerPredicate.test(new IllegalStateException("Unexpected error"))).isTrue();
    }

    @Test
    void circuitBreakerPredicate_shouldIgnoreNullControlExceptionsAndStandard4xxEvenWhenWrapped() {
        RequestNotPermitted rateLimitError = RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("esi"));
        CallNotPermittedException circuitOpen =
                CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("esi"));

        assertThat(circuitBreakerPredicate.test(null)).isFalse();
        assertThat(circuitBreakerPredicate.test(rateLimitError)).isFalse();
        assertThat(circuitBreakerPredicate.test(circuitOpen)).isFalse();
        assertThat(circuitBreakerPredicate.test(new RuntimeException("Wrapped rate limit", rateLimitError))).isFalse();
        assertThat(circuitBreakerPredicate.test(new RuntimeException("Wrapped circuit open", circuitOpen))).isFalse();
        assertThat(circuitBreakerPredicate.test(httpResponseException(400, "Bad Request"))).isFalse();
        assertThat(circuitBreakerPredicate.test(httpResponseException(401, "Unauthorized"))).isFalse();
        assertThat(circuitBreakerPredicate.test(httpResponseException(403, "Forbidden"))).isFalse();
        assertThat(circuitBreakerPredicate.test(httpResponseException(404, "Not Found"))).isFalse();
        assertThat(circuitBreakerPredicate.test(new RuntimeException("Wrapped 404", httpResponseException(404, "Not Found")))).isFalse();
    }

    @Test
    void retryPredicate_shouldRetryTransient5xx429AndTransportErrorsIncludingWrappedCauses() {
        assertThat(retryPredicate.test(httpResponseException(500, "Internal Server Error"))).isTrue();
        assertThat(retryPredicate.test(httpResponseException(502, "Bad Gateway"))).isTrue();
        assertThat(retryPredicate.test(httpResponseException(503, "Service Unavailable"))).isTrue();
        assertThat(retryPredicate.test(httpResponseException(504, "Gateway Timeout"))).isTrue();
        assertThat(retryPredicate.test(httpResponseException(429, "Too Many Requests"))).isTrue();
        assertThat(retryPredicate.test(new RuntimeException("Wrapped 503", httpResponseException(503, "Service Unavailable")))).isTrue();
        assertThat(retryPredicate.test(new RuntimeException("Wrapped 429", httpResponseException(429, "Too Many Requests")))).isTrue();
        assertThat(retryPredicate.test(
                new WebClientRequestException(new IOException("Connection refused"), HttpMethod.GET, TEST_URI, HttpHeaders.EMPTY)
        )).isTrue();
        assertThat(retryPredicate.test(new IOException("Network unreachable"))).isTrue();
        assertThat(retryPredicate.test(new TimeoutException("Read timeout"))).isTrue();
        assertThat(retryPredicate.test(new RuntimeException("Wrapped IO", new IOException("Reset by peer")))).isTrue();
        assertThat(retryPredicate.test(new RuntimeException("Wrapped timeout", new TimeoutException("Timed out")))).isTrue();
    }

    @Test
    void retryPredicate_shouldNotRetry420Other4xxControlExceptionsOrDeepNonRetryableChains() {
        CallNotPermittedException circuitOpen =
                CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("esi"));
        RequestNotPermitted rateLimited =
                RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("esi"));

        assertThat(retryPredicate.test(null)).isFalse();
        assertThat(retryPredicate.test(circuitOpen)).isFalse();
        assertThat(retryPredicate.test(rateLimited)).isFalse();
        assertThat(retryPredicate.test(new RuntimeException("Wrapped circuit open", circuitOpen))).isFalse();
        assertThat(retryPredicate.test(new RuntimeException("Wrapped rate limited", rateLimited))).isFalse();
        assertThat(retryPredicate.test(httpResponseException(420, "Error Limited"))).isFalse();
        assertThat(retryPredicate.test(httpResponseException(400, "Bad Request"))).isFalse();
        assertThat(retryPredicate.test(httpResponseException(401, "Unauthorized"))).isFalse();
        assertThat(retryPredicate.test(httpResponseException(403, "Forbidden"))).isFalse();
        assertThat(retryPredicate.test(httpResponseException(404, "Not Found"))).isFalse();
        assertThat(retryPredicate.test(new RuntimeException("Wrapped 404", httpResponseException(404, "Not Found")))).isFalse();
        assertThat(retryPredicate.test(new IllegalArgumentException("Bad argument"))).isFalse();

        Throwable deepChain = new IllegalStateException("root");
        for (int i = 0; i < 12; i++) {
            deepChain = new RuntimeException("level-" + i, deepChain);
        }
        assertThat(retryPredicate.test(deepChain)).isFalse();
        assertThat(circuitBreakerPredicate.test(deepChain)).isTrue();
    }

    private static WebClientResponseException httpResponseException(int status, String reason) {
        return WebClientResponseException.create(status, reason, HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }
}
