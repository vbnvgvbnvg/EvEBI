package com.github.serbentd.eve.poller.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Strongly-typed, immutable configuration properties for the ESI market order ingestion poller.
 * Bound to the {@code eve.poller} prefix in application configuration.
 */
@Validated
@ConfigurationProperties(prefix = "eve.poller")
public record PollerProperties(
        @NotNull @Valid EsiProperties esi,
        @NotNull @Valid SchedulingProperties scheduling,
        @NotNull @Valid AmqpProperties amqp
) {

    /**
     * ESI HTTP client configuration.
     *
     * @param baseUrl                 CCP ESI API base URL
     * @param userAgent               mandatory descriptive User-Agent header
     * @param connectTimeout          HTTP connection establishment timeout
     * @param readTimeout             HTTP response read timeout
     * @param maxInMemorySize         maximum in-memory codec buffer size for response decoding
     * @param minErrorRemainThreshold minimum remaining error budget before backing off
     */
    public record EsiProperties(
            @NotBlank String baseUrl,
            @NotBlank String userAgent,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @NotNull DataSize maxInMemorySize,
            @Positive int minErrorRemainThreshold
    ) {
    }

    /**
     * Region market polling scheduling configuration.
     *
     * @param regions      target solar region IDs to scrape
     * @param initialDelay initial delay before the first polling run
     * @param fixedRate    duration between consecutive polling runs
     */
    public record SchedulingProperties(
            @NotEmpty List<@NotNull @Positive Long> regions,
            @NotNull Duration initialDelay,
            @NotNull Duration fixedRate
    ) {
    }

    /**
     * RabbitMQ publication coordinates.
     *
     * @param exchange   topic exchange to publish order snapshot events to
     * @param routingKey routing key pattern for order events
     */
    public record AmqpProperties(
            @NotBlank String exchange,
            @NotBlank String routingKey
    ) {
    }
}
