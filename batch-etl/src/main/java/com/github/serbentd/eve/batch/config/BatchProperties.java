package com.github.serbentd.eve.batch.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Immutable configuration properties for Spring Batch ETL chunk processing.
 */
@ConfigurationProperties(prefix = "eve.batch")
@Validated
public record BatchProperties(
        @Valid @NotNull ChunkSizeProperties chunkSize
) {

    /**
     * Step-specific chunk sizing for fine-grained throughput and memory tuning.
     */
    public record ChunkSizeProperties(
            @NotNull @Positive Integer dailyMarketSummary,
            @NotNull @Positive Integer orderBookLiquidity
    ) {}
}
