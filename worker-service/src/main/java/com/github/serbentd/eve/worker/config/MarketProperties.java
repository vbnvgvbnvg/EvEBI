package com.github.serbentd.eve.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration properties for EVE market message queues and exchanges.
 * Bound to the {@code eve.market} configuration prefix in {@code application.yml}.
 *
 * @param exchange   the AMQP topic exchange name (e.g. eve.market.orders)
 * @param queue      the worker persistence queue name (e.g. eve.market.orders.worker)
 * @param routingKey the AMQP routing key pattern (e.g. eve.market.orders.#)
 * @param batchSize  the maximum number of orders to process and upsert in a single persistence chunk
 */
@ConfigurationProperties(prefix = "eve.market")
public record MarketProperties(
        String exchange,
        String queue,
        String routingKey,
        int batchSize
) {
}
