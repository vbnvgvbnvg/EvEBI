package com.github.serbentd.eve.batch.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Immutable configuration properties for Spring Batch ETL chunk processing and SDE synchronizations.
 */
@ConfigurationProperties(prefix = "eve.batch")
@Validated
public record BatchProperties(
        @Valid @NotNull ChunkSizeProperties chunkSize,
        @Valid @NotNull SdeProperties sde
) {

    /**
     * Step-specific chunk sizing for fine-grained throughput and memory tuning.
     */
    public record ChunkSizeProperties(
            @NotNull @Positive Integer dailyMarketSummary,
            @NotNull @Positive Integer orderBookLiquidity
    ) {}

    /**
     * Configuration properties for Static Data Export (SDE) external feeds and streaming synchronization.
     */
    public record SdeProperties(
            @NotBlank String itemsUrl,
            @NotBlank String stationsUrl,
            @NotBlank String userAgent,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @NotNull @Positive Integer chunkSize
    ) {}
}
