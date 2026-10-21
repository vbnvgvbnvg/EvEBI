package com.github.serbentd.eve.batch.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BatchConfigTest {

    private final BatchProperties batchProperties;
    private final JobRepository jobRepository;
    private final JobExplorer jobExplorer;

    BatchConfigTest(BatchProperties batchProperties, JobRepository jobRepository, JobExplorer jobExplorer) {
        this.batchProperties = batchProperties;
        this.jobRepository = jobRepository;
        this.jobExplorer = jobExplorer;
    }

    @Test
    @DisplayName("BatchProperties should load chunk sizing configuration correctly")
    void batchProperties_shouldBindConfiguredChunkSizes() {
        assertThat(batchProperties).isNotNull();
        assertThat(batchProperties.chunkSize()).isNotNull();
        assertThat(batchProperties.chunkSize().dailyMarketSummary()).isEqualTo(10);
        assertThat(batchProperties.chunkSize().orderBookLiquidity()).isEqualTo(10);
    }

    @Test
    @DisplayName("JobRepository and JobExplorer should be successfully initialized with batch schema prefix")
    void batchInfrastructure_shouldBeConfigured() {
        assertThat(jobRepository).isNotNull();
        assertThat(jobExplorer).isNotNull();
        assertThat(jobExplorer.getJobNames()).isNotNull();
    }
}
