package com.github.serbentd.eve.batch.config;

import org.springframework.batch.core.configuration.support.DefaultBatchConfiguration;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.integration.config.annotation.EnableBatchIntegration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;

/**
 * Spring Batch 5 core configuration.
 * Extends {@link DefaultBatchConfiguration} to route all Spring Batch metadata
 * tables and sequences into the dedicated {@code batch} database schema.
 */
@Configuration
@EnableConfigurationProperties(BatchProperties.class)
@EnableBatchIntegration
@EnableScheduling
public class BatchConfig extends DefaultBatchConfiguration {

    private static final String BATCH_TABLE_PREFIX = "batch.BATCH_";

    @Override
    protected String getTablePrefix() {
        return BATCH_TABLE_PREFIX;
    }

    @Bean
    @Primary
    @Override
    public JobLauncher jobLauncher() throws org.springframework.batch.core.configuration.BatchConfigurationException {
        return super.jobLauncher();
    }

    @Bean
    @Override
    public JobOperator jobOperator() throws org.springframework.batch.core.configuration.BatchConfigurationException {
        return super.jobOperator();
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

    @Bean
    public JobLauncher asyncJobLauncher(
            JobRepository jobRepository,
            @Qualifier("asyncBatchTaskExecutor") TaskExecutor asyncBatchTaskExecutor) throws Exception {
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        jobLauncher.setJobRepository(jobRepository);
        jobLauncher.setTaskExecutor(asyncBatchTaskExecutor);
        jobLauncher.afterPropertiesSet();
        return jobLauncher;
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
