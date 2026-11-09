package com.github.serbentd.eve.batch.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/**
 * Immutable configuration properties for Spring Batch ETL chunk processing, SDE synchronizations,
 * and distributed remote partitioning.
 */
@ConfigurationProperties(prefix = "eve.batch")
@Validated
public record BatchProperties(
        @Valid @NotNull ChunkSizeProperties chunkSize,
        @Valid @NotNull SdeProperties sde,
        @Valid @NotNull RemotePartitioningProperties remotePartitioning
) {

    /**
     * Step-level chunk sizes governing transaction boundaries and JDBC batch sizes.
     */
    public record ChunkSizeProperties(
            @NotNull @Positive Integer dailyMarketSummary,
            @NotNull @Positive Integer orderBookLiquidity
    ) {}

    /**
     * Configuration properties for EVE Static Data Export (SDE) HTTP downloads and batch sync.
     */
    public record SdeProperties(
            @NotBlank String itemsUrl,
            @NotBlank String stationsUrl,
            @NotBlank String userAgent,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @NotNull @Positive Integer chunkSize
    ) {}

    /**
     * Configuration properties for distributed Spring Batch remote partitioning across market regions.
     *
     * @param role                 node role ({@code STANDALONE} runs local single-node steps;
     *                             {@code MANAGER} publishes partition requests; {@code WORKER} consumes them)
     * @param amqpFlowsEnabled     whether AMQP inbound/outbound integration flows are registered (disabled in
     *                             Docker-free tests that bridge channels in-memory)
     * @param exchange             topic exchange for partition requests
     * @param requestsQueue        durable queue for partition requests
     * @param requestsRoutingKey   routing key for partition requests
     * @param deadLetterExchange   direct exchange receiving rejected/failed partition requests
     * @param deadLetterQueue      durable queue storing dead-lettered partition requests for inspection/alerting
     * @param deadLetterRoutingKey routing key binding the dead-letter queue to the dead-letter exchange
     * @param pollInterval         manager polling interval against {@code JobRepository}
     * @param timeout              manager wait timeout before failing an incomplete partitioned step
     * @param workerConcurrency    concurrent AMQP consumers per worker node
     * @param prefetchCount        AMQP prefetch count per consumer (1 ensures fair on-demand dispatch)
     * @param heavyRegionSlices    mapping of region ID to quantile item-range slice count for high-volume hubs
     */
    public record RemotePartitioningProperties(
            @NotNull PartitionRole role,
            @NotNull Boolean amqpFlowsEnabled,
            @NotBlank String exchange,
            @NotBlank String requestsQueue,
            @NotBlank String requestsRoutingKey,
            @NotBlank String deadLetterExchange,
            @NotBlank String deadLetterQueue,
            @NotBlank String deadLetterRoutingKey,
            @NotNull Duration pollInterval,
            @NotNull Duration timeout,
            @NotNull @Positive Integer workerConcurrency,
            @NotNull @Positive Integer prefetchCount,
            @NotEmpty Map<@NotNull Long, @NotNull @Positive Integer> heavyRegionSlices
    ) {
        /**
         * Returns {@code true} when this node operates in distributed partitioned mode
         * ({@link PartitionRole#MANAGER} or {@link PartitionRole#WORKER}).
         */
        public boolean isPartitioned() {
            return role != PartitionRole.STANDALONE;
        }
    }
}
