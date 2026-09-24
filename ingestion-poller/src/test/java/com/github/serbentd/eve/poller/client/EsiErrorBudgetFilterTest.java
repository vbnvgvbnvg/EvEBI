package com.github.serbentd.eve.poller.client;

import com.github.serbentd.eve.poller.config.PollerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EsiErrorBudgetFilterTest {

    private static final int THRESHOLD = 20;

    private EsiErrorBudgetFilter filter;

    @BeforeEach
    void setUp() {
        PollerProperties.EsiProperties esi = new PollerProperties.EsiProperties(
                "https://esi.evetech.net",
                "EvEBI-Test/1.0",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                DataSize.ofMegabytes(1),
                THRESHOLD
        );
        PollerProperties.SchedulingProperties scheduling = new PollerProperties.SchedulingProperties(
                List.of(10000002L),
                Duration.ofHours(1),
                Duration.ofHours(1)
        );
        PollerProperties.AmqpProperties amqp = new PollerProperties.AmqpProperties(
                "eve.market.orders",
                "eve.market.orders.raw"
        );
        PollerProperties properties = new PollerProperties(esi, scheduling, amqp);
        filter = new EsiErrorBudgetFilter(properties);
    }

    @Test
    void filter_whenBudgetAboveThreshold_shouldExecuteImmediatelyAndTrackHeaders() {
        ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("https://esi.evetech.net/test")).build();
        ClientResponse mockResponse = ClientResponse.create(HttpStatus.OK)
                .header("X-ESI-Error-Limit-Remain", "85")
                .header("X-ESI-Error-Limit-Reset", "45")
                .build();
        ExchangeFunction next = req -> Mono.just(mockResponse);

        StepVerifier.create(filter.filter(request, next))
                .expectNext(mockResponse)
                .verifyComplete();

        assertThat(filter.getErrorLimitRemain()).isEqualTo(85);
        assertThat(filter.getErrorLimitReset()).isEqualTo(45);
    }

    @Test
    void filter_whenBudgetDropsBelowThreshold_shouldDelaySubsequentRequest() {
        ClientRequest request1 = ClientRequest.create(HttpMethod.GET, URI.create("https://esi.evetech.net/req1")).build();
        ClientResponse response1 = ClientResponse.create(HttpStatus.BAD_REQUEST)
                .header("X-ESI-Error-Limit-Remain", "10")
                .header("X-ESI-Error-Limit-Reset", "2")
                .build();
        ExchangeFunction next1 = req -> Mono.just(response1);

        // First request receives headers dropping budget below threshold
        StepVerifier.create(filter.filter(request1, next1))
                .expectNext(response1)
                .verifyComplete();

        assertThat(filter.getErrorLimitRemain()).isEqualTo(10);
        assertThat(filter.getErrorLimitReset()).isEqualTo(2);

        // Second request should now pause for 2 seconds (using virtual time)
        ClientRequest request2 = ClientRequest.create(HttpMethod.GET, URI.create("https://esi.evetech.net/req2")).build();
        ClientResponse response2 = ClientResponse.create(HttpStatus.OK)
                .header("X-ESI-Error-Limit-Remain", "100")
                .header("X-ESI-Error-Limit-Reset", "60")
                .build();
        ExchangeFunction next2 = req -> Mono.just(response2);

        StepVerifier.withVirtualTime(() -> filter.filter(request2, next2))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(2))
                .thenAwait(Duration.ofSeconds(2))
                .expectNext(response2)
                .verifyComplete();

        assertThat(filter.getErrorLimitRemain()).isEqualTo(100);
        assertThat(filter.getErrorLimitReset()).isEqualTo(60);
    }

    @Test
    void filter_whenResetIsZeroOrNegative_shouldDefaultToAtLeastOneSecondPause() {
        ClientRequest request1 = ClientRequest.create(HttpMethod.GET, URI.create("https://esi.evetech.net/req1")).build();
        ClientResponse response1 = ClientResponse.create(HttpStatus.BAD_REQUEST)
                .header("X-ESI-Error-Limit-Remain", "5")
                .header("X-ESI-Error-Limit-Reset", "0")
                .build();
        ExchangeFunction next1 = req -> Mono.just(response1);

        StepVerifier.create(filter.filter(request1, next1))
                .expectNext(response1)
                .verifyComplete();

        ClientRequest request2 = ClientRequest.create(HttpMethod.GET, URI.create("https://esi.evetech.net/req2")).build();
        ClientResponse response2 = ClientResponse.create(HttpStatus.OK).build();
        ExchangeFunction next2 = req -> Mono.just(response2);

        // Math.max(0, 1) = 1s delay
        StepVerifier.withVirtualTime(() -> filter.filter(request2, next2))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(1))
                .thenAwait(Duration.ofSeconds(1))
                .expectNext(response2)
                .verifyComplete();
    }

    @Test
    void filter_whenHeadersMissingOrMalformed_shouldNotFailOrThrow() {
        ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("https://esi.evetech.net/req")).build();
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("X-ESI-Error-Limit-Remain", "invalid-int")
                .header("X-ESI-Error-Limit-Reset", "not-a-number")
                .build();
        ExchangeFunction next = req -> Mono.just(response);

        StepVerifier.create(filter.filter(request, next))
                .expectNext(response)
                .verifyComplete();

        // Initial default values remain unchanged
        assertThat(filter.getErrorLimitRemain()).isEqualTo(Integer.MAX_VALUE);
        assertThat(filter.getErrorLimitReset()).isEqualTo(0);
    }
}
