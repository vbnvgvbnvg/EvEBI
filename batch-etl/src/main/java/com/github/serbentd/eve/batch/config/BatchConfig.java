package com.github.serbentd.eve.batch.config;

import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration;
import org.springframework.batch.integration.config.annotation.EnableBatchIntegration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;

/**
 * Spring Batch 6 core configuration.
 * Extends {@link JdbcDefaultBatchConfiguration} to route all Spring Batch metadata
 * tables and sequences into the dedicated {@code batch} database schema.
 */
@Configuration
@EnableConfigurationProperties(BatchProperties.class)
@EnableBatchIntegration
@EnableScheduling
public class BatchConfig extends JdbcDefaultBatchConfiguration {

    private static final String BATCH_TABLE_PREFIX = "batch.BATCH_";

    private final BatchProperties batchProperties;

    public BatchConfig(BatchProperties batchProperties) {
        this.batchProperties = batchProperties;
    }

    @Override
    protected String getTablePrefix() {
        return BATCH_TABLE_PREFIX;
    }

    @Override
    protected TaskExecutor getTaskExecutor() {
        if (batchProperties.kubernetes().isOneShotJob()) {
            return new SyncTaskExecutor();
        }
        return asyncBatchTaskExecutor(batchProperties);
    }

    @Bean
    public TaskExecutor asyncBatchTaskExecutor(BatchProperties batchProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(batchProperties.asyncLauncher().corePoolSize());
        executor.setMaxPoolSize(batchProperties.asyncLauncher().maxPoolSize());
        executor.setQueueCapacity(batchProperties.asyncLauncher().queueCapacity());
        executor.setThreadNamePrefix("batch-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(
                Math.toIntExact(batchProperties.asyncLauncher().awaitTermination().toSeconds()));
        executor.initialize();
        return executor;
    }

    @Bean("sdeHttpClient")
    public HttpClient sdeHttpClient(BatchProperties batchProperties) {
        return HttpClient.newBuilder()
                .connectTimeout(batchProperties.sde().connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Bean("esiHttpClient")
    public HttpClient esiHttpClient(BatchProperties batchProperties) {
        return HttpClient.newBuilder()
                .connectTimeout(batchProperties.esi().connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
