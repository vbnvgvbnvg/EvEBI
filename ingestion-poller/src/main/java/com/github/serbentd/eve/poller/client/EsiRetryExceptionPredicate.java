package com.github.serbentd.eve.poller.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Retry exception predicate for CCP ESI outbound requests.
 * <p>
 * Permits retries exclusively for transient upstream 5xx responses, HTTP 429,
 * and network/socket/timeout exceptions. Explicitly suppresses retries for
 * HTTP 420 (CCP error limit reached), other 4xx client errors, and Resilience4j
 * short-circuit/rate-limiter control exceptions.
 */
public class EsiRetryExceptionPredicate implements Predicate<Throwable> {

    private static final int HTTP_STATUS_TOO_MANY_REQUESTS = 429;
    private static final int MAX_CAUSE_DEPTH = 10;

    public EsiRetryExceptionPredicate() {
    }

    @Override
    public boolean test(Throwable throwable) {
        if (throwable == null
                || throwable instanceof CallNotPermittedException
                || throwable instanceof RequestNotPermitted) {
            return false;
        }
        if (throwable instanceof WebClientResponseException responseException) {
            return isRetryableResponse(responseException);
        }
        if (isTransientTransportException(throwable)) {
            return true;
        }
        return hasRetryableCause(throwable);
    }

    private boolean isRetryableResponse(WebClientResponseException responseException) {
        return responseException.getStatusCode().is5xxServerError()
                || responseException.getStatusCode().value() == HTTP_STATUS_TOO_MANY_REQUESTS;
    }

    private boolean isTransientTransportException(Throwable throwable) {
        return throwable instanceof WebClientRequestException
                || throwable instanceof IOException
                || throwable instanceof TimeoutException;
    }

    private boolean hasRetryableCause(Throwable throwable) {
        Throwable current = throwable.getCause();
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current instanceof CallNotPermittedException || current instanceof RequestNotPermitted) {
                return false;
            }
            if (current instanceof WebClientResponseException responseException) {
                return isRetryableResponse(responseException);
            }
            if (isTransientTransportException(current)) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }
}
