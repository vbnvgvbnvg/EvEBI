package com.github.serbentd.eve.worker.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Strongly-typed configuration properties for EVE market message queues, exchanges, and dead-letter routing.
 * Bound to the {@code eve.market} configuration prefix in {@code application.yml}.
 *
 * @param exchange             the AMQP topic exchange name (e.g. eve.market.orders)
 * @param queue                the worker persistence queue name (e.g. eve.market.orders.worker)
 * @param routingKey           the AMQP routing key pattern (e.g. eve.market.orders.#)
 * @param deadLetterExchange   the direct dead-letter exchange name (e.g. eve.market.orders.dlx)
 * @param deadLetterQueue      the dead-letter queue name (e.g. eve.market.orders.dlq)
 * @param deadLetterRoutingKey the routing key binding the DLQ to the DLX
 * @param batchSize            the maximum number of orders to process and upsert in a single persistence chunk
 * @param queueTtl             the time-to-live duration for messages in the queue
 * @param queueMaxLength       the maximum number of messages allowed in the queue before dropping head
 */
@Validated
@ConfigurationProperties(prefix = "eve.market")
public record MarketProperties(
        @NotBlank String exchange,
        @NotBlank String queue,
        @NotBlank String routingKey,
        @NotBlank String deadLetterExchange,
        @NotBlank String deadLetterQueue,
        @NotBlank String deadLetterRoutingKey,
        @Positive int batchSize,
        @NotNull Duration queueTtl,
        @Positive int queueMaxLength
) {
}
