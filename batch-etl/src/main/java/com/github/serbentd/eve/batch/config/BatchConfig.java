package com.github.serbentd.eve.batch.config;

import org.springframework.batch.core.configuration.support.DefaultBatchConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Batch 5 core configuration.
 * Extends {@link DefaultBatchConfiguration} to route all Spring Batch metadata
 * tables and sequences into the dedicated {@code batch} database schema.
 */
@Configuration
@EnableConfigurationProperties(BatchProperties.class)
public class BatchConfig extends DefaultBatchConfiguration {

    private static final String BATCH_TABLE_PREFIX = "batch.BATCH_";

    @Override
    protected String getTablePrefix() {
        return BATCH_TABLE_PREFIX;
    }
}
