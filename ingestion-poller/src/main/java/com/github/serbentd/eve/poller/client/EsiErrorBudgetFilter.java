package com.github.serbentd.eve.poller.client;

import com.github.serbentd.eve.poller.config.PollerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reactive WebClient filter monitoring CCP Games' ESI error budget headers.
 * <p>
 * Inspects {@code X-ESI-Error-Limit-Remain} and {@code X-ESI-Error-Limit-Reset} on every
 * response. If the remaining error budget drops to or below the configured threshold,
 * outbound requests are temporarily delayed until the reset window elapses to protect
 * against IP-level bans.
 */
@Slf4j
@Component
public class EsiErrorBudgetFilter implements ExchangeFilterFunction {

    private static final String HEADER_ERROR_LIMIT_REMAIN = "X-ESI-Error-Limit-Remain";
    private static final String HEADER_ERROR_LIMIT_RESET = "X-ESI-Error-Limit-Reset";

    private final PollerProperties properties;
    private final AtomicInteger errorLimitRemain = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger errorLimitReset = new AtomicInteger(0);

    public EsiErrorBudgetFilter(PollerProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        int currentRemain = errorLimitRemain.get();
        int threshold = properties.esi().minErrorRemainThreshold();

        if (currentRemain <= threshold) {
            int resetSeconds = Math.max(errorLimitReset.get(), 1);
            log.warn("ESI error budget critically low ({}/{} threshold). Pausing for {}s before request: {} {}",
                    currentRemain, threshold, resetSeconds, request.method(), request.url());

            return Mono.delay(Duration.ofSeconds(resetSeconds))
                    .then(next.exchange(request))
                    .doOnNext(this::inspectHeaders);
        }

        return next.exchange(request).doOnNext(this::inspectHeaders);
    }

    private void inspectHeaders(ClientResponse response) {
        String remainHeader = response.headers().asHttpHeaders().getFirst(HEADER_ERROR_LIMIT_REMAIN);
        String resetHeader = response.headers().asHttpHeaders().getFirst(HEADER_ERROR_LIMIT_RESET);

        if (remainHeader != null) {
            try {
                int remain = Integer.parseInt(remainHeader.trim());
                errorLimitRemain.set(remain);

                if (remain <= properties.esi().minErrorRemainThreshold()) {
                    log.warn("ESI error limit remaining reached critical level: {} (threshold: {})",
                            remain, properties.esi().minErrorRemainThreshold());
                }
            } catch (NumberFormatException e) {
                log.debug("Failed to parse {}: {}", HEADER_ERROR_LIMIT_REMAIN, remainHeader);
            }
        }

        if (resetHeader != null) {
            try {
                int reset = Integer.parseInt(resetHeader.trim());
                errorLimitReset.set(reset);
            } catch (NumberFormatException e) {
                log.debug("Failed to parse {}: {}", HEADER_ERROR_LIMIT_RESET, resetHeader);
            }
        }
    }

    /**
     * Current remaining error budget reported by CCP ESI.
     */
    public int getErrorLimitRemain() {
        return errorLimitRemain.get();
    }

    /**
     * Current seconds until error budget reset reported by CCP ESI.
     */
    public int getErrorLimitReset() {
        return errorLimitReset.get();
    }
}
