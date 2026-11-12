package com.github.serbentd.eve.batch.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BatchConfigTest {

    private final BatchProperties batchProperties;
    private final JobRepository jobRepository;
    private final JobExplorer jobExplorer;
    private final HttpClient sdeHttpClient;
    private final HttpClient esiHttpClient;
    private final TaskExecutor asyncBatchTaskExecutor;
    private final JobLauncher asyncJobLauncher;

    BatchConfigTest(
            BatchProperties batchProperties,
            JobRepository jobRepository,
            JobExplorer jobExplorer,
            @Qualifier("sdeHttpClient") HttpClient sdeHttpClient,
            @Qualifier("esiHttpClient") HttpClient esiHttpClient,
            @Qualifier("asyncBatchTaskExecutor") TaskExecutor asyncBatchTaskExecutor,
            @Qualifier("asyncJobLauncher") JobLauncher asyncJobLauncher) {
        this.batchProperties = batchProperties;
        this.jobRepository = jobRepository;
        this.jobExplorer = jobExplorer;
        this.sdeHttpClient = sdeHttpClient;
        this.esiHttpClient = esiHttpClient;
        this.asyncBatchTaskExecutor = asyncBatchTaskExecutor;
        this.asyncJobLauncher = asyncJobLauncher;
    }

    @Test
    @DisplayName("BatchProperties should load chunk sizing and async launcher configuration correctly")
    void batchProperties_shouldBindConfiguredChunkSizes() {
        assertThat(batchProperties).isNotNull();
        assertThat(batchProperties.chunkSize()).isNotNull();
        assertThat(batchProperties.chunkSize().dailyMarketSummary()).isEqualTo(10);
        assertThat(batchProperties.chunkSize().orderBookLiquidity()).isEqualTo(10);

        assertThat(batchProperties.asyncLauncher()).isNotNull();
        assertThat(batchProperties.asyncLauncher().corePoolSize()).isEqualTo(2);
        assertThat(batchProperties.asyncLauncher().maxPoolSize()).isEqualTo(4);
        assertThat(batchProperties.asyncLauncher().queueCapacity()).isEqualTo(10);

        assertThat(batchProperties.sde()).isNotNull();
        assertThat(batchProperties.sde().itemsUrl()).isEqualTo("http://localhost:8089/dump/latest/invTypes.csv");
        assertThat(batchProperties.sde().stationsUrl()).isEqualTo("http://localhost:8089/dump/latest/staStations.csv");
        assertThat(batchProperties.sde().userAgent()).isEqualTo("EveBI-Test/1.0");
        assertThat(batchProperties.sde().connectTimeout()).hasSeconds(5);
        assertThat(batchProperties.sde().readTimeout()).hasSeconds(5);
        assertThat(batchProperties.sde().chunkSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("JobRepository and JobExplorer should be successfully initialized with batch schema prefix")
    void batchInfrastructure_shouldBeConfigured() {
        assertThat(jobRepository).isNotNull();
        assertThat(jobExplorer).isNotNull();
        assertThat(jobExplorer.getJobNames()).isNotNull();
        assertThat(sdeHttpClient).isNotNull();
        assertThat(esiHttpClient).isNotNull();
        assertThat(sdeHttpClient.connectTimeout()).isPresent().hasValue(batchProperties.sde().connectTimeout());
        assertThat(esiHttpClient.connectTimeout()).isPresent().hasValue(batchProperties.esi().connectTimeout());
        assertThat(asyncBatchTaskExecutor).isNotNull();
        assertThat(asyncJobLauncher).isNotNull();
    }
}
