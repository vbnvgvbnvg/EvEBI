package com.github.serbentd.eve.poller.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.function.Predicate;

/**
 * CircuitBreaker failure classification predicate for CCP ESI outbound requests.
 * <p>
 * Records upstream 5xx errors, HTTP 420 (CCP ESI error limit reached), HTTP 429
 * (Too Many Requests), and network/runtime faults as failures while ignoring local
 * rate-limiter/circuit-breaker control exceptions and standard 4xx client responses
 * (e.g., 404 for expired pages), including when wrapped inside reactive cause chains.
 */
public class EsiCircuitBreakerFailurePredicate implements Predicate<Throwable> {

    private static final int HTTP_STATUS_ESI_ERROR_LIMITED = 420;
    private static final int HTTP_STATUS_TOO_MANY_REQUESTS = 429;
    private static final int MAX_CAUSE_DEPTH = 10;

    public EsiCircuitBreakerFailurePredicate() {
    }

    @Override
    public boolean test(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current instanceof RequestNotPermitted || current instanceof CallNotPermittedException) {
                return false;
            }
            if (current instanceof WebClientResponseException responseException) {
                return isFailureResponse(responseException);
            }
            current = current.getCause();
            depth++;
        }
        return true;
    }

    private boolean isFailureResponse(WebClientResponseException responseException) {
        int status = responseException.getStatusCode().value();
        return responseException.getStatusCode().is5xxServerError()
                || status == HTTP_STATUS_ESI_ERROR_LIMITED
                || status == HTTP_STATUS_TOO_MANY_REQUESTS;
    }
}
