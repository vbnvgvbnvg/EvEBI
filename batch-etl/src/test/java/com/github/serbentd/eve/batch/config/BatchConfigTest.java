package com.github.serbentd.eve.batch.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BatchConfigTest {

    private final BatchProperties batchProperties;
    private final JobRepository jobRepository;
    private final HttpClient sdeHttpClient;
    private final HttpClient esiHttpClient;
    private final TaskExecutor asyncBatchTaskExecutor;
    private final JobOperator jobOperator;
    private final JdbcTemplate jdbcTemplate;

    BatchConfigTest(
            BatchProperties batchProperties,
            JobRepository jobRepository,
            @Qualifier("sdeHttpClient") HttpClient sdeHttpClient,
            @Qualifier("esiHttpClient") HttpClient esiHttpClient,
            @Qualifier("asyncBatchTaskExecutor") TaskExecutor asyncBatchTaskExecutor,
            JobOperator jobOperator,
            JdbcTemplate jdbcTemplate) {
        this.batchProperties = batchProperties;
        this.jobRepository = jobRepository;
        this.sdeHttpClient = sdeHttpClient;
        this.esiHttpClient = esiHttpClient;
        this.asyncBatchTaskExecutor = asyncBatchTaskExecutor;
        this.jobOperator = jobOperator;
        this.jdbcTemplate = jdbcTemplate;
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

        assertThat(batchProperties.kubernetes()).isNotNull();
        assertThat(batchProperties.kubernetes().podName()).isEqualTo("test-pod");
        assertThat(batchProperties.kubernetes().podNodeName()).isEqualTo("test-node");
        assertThat(batchProperties.kubernetes().executionMode()).isEqualTo(BatchExecutionMode.DAEMON);
        assertThat(batchProperties.kubernetes().exitOnCompletion()).isFalse();
        assertThat(batchProperties.kubernetes().isOneShotJob()).isFalse();
    }

    @Test
    @DisplayName("JobRepository and JobOperator should be successfully initialized with batch schema prefix")
    void batchInfrastructure_shouldBeConfigured() {
        assertThat(jobRepository).isNotNull();
        assertThat(jobRepository.getJobNames()).isNotNull();
        assertThat(sdeHttpClient).isNotNull();
        assertThat(esiHttpClient).isNotNull();
        assertThat(sdeHttpClient.connectTimeout()).isPresent().hasValue(batchProperties.sde().connectTimeout());
        assertThat(esiHttpClient.connectTimeout()).isPresent().hasValue(batchProperties.esi().connectTimeout());
        assertThat(asyncBatchTaskExecutor).isNotNull();
        assertThat(jobOperator).isNotNull();

        Long nextInstanceSeq = jdbcTemplate.queryForObject("SELECT NEXTVAL('batch.BATCH_JOB_INSTANCE_SEQ')", Long.class);
        assertThat(nextInstanceSeq).isPositive();
    }
}
