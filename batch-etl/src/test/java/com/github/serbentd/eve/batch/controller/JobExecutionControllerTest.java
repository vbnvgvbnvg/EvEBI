package com.github.serbentd.eve.batch.controller;

import com.github.serbentd.eve.batch.config.BatchJobParametersFactory;
import com.github.serbentd.eve.batch.dto.JobLaunchResponseDto;
import com.github.serbentd.eve.batch.job.daily.DailyMarketSummaryJobConfig;
import com.github.serbentd.eve.batch.job.liquidity.OrderBookLiquidityJobConfig;
import com.github.serbentd.eve.batch.job.sde.SdeSyncJobConfig;
import com.github.serbentd.eve.batch.service.DateDimensionService;
import com.github.serbentd.eve.batch.service.EsiRegionSyncService;
import com.github.serbentd.eve.batch.service.TimeDimensionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:batch_controller_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@AutoConfigureMockMvc
class JobExecutionControllerTest {

    private static final String REGIONS_SYNC_URL = "/api/v1/jobs/regions-sync";
    private static final String SEED_DIMENSIONS_URL = "/api/v1/jobs/seed-dimensions";
    private static final String SDE_SYNC_URL = "/api/v1/jobs/sde-sync";
    private static final String DAILY_SUMMARY_URL = "/api/v1/jobs/daily-summary";
    private static final String LIQUIDITY_URL = "/api/v1/jobs/liquidity";
    private static final String EXECUTIONS_URL = "/api/v1/jobs/executions/";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY_VALUE = "test-api-key";

    private final MockMvc mockMvc;
    private final JobRepository jobRepository;
    private final JsonMapper jsonMapper;

    JobExecutionControllerTest(MockMvc mockMvc, JobRepository jobRepository, JsonMapper jsonMapper) {
        this.mockMvc = mockMvc;
        this.jobRepository = jobRepository;
        this.jsonMapper = jsonMapper;
    }

    @AfterEach
    void awaitRunningJobs() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        Set<JobExecution> runningSde = Set.of();
        Set<JobExecution> runningDaily = Set.of();
        Set<JobExecution> runningLiq = Set.of();
        while (System.currentTimeMillis() < deadline) {
            runningSde = jobRepository.findRunningJobExecutions(SdeSyncJobConfig.JOB_NAME);
            runningDaily = jobRepository.findRunningJobExecutions(DailyMarketSummaryJobConfig.JOB_NAME);
            runningLiq = jobRepository.findRunningJobExecutions(OrderBookLiquidityJobConfig.JOB_NAME);
            if (runningSde.isEmpty() && runningDaily.isEmpty() && runningLiq.isEmpty()) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(runningSde).as("Running SDE sync executions should drain after test").isEmpty();
        assertThat(runningDaily).as("Running daily summary executions should drain after test").isEmpty();
        assertThat(runningLiq).as("Running liquidity executions should drain after test").isEmpty();
    }

    @Test
    @DisplayName("POST /api/v1/jobs/regions-sync should return HTTP 200 and success status")
    void triggerRegionsSync_shouldReturnHttp200() throws Exception {
        mockMvc.perform(post(REGIONS_SYNC_URL).header(API_KEY_HEADER, API_KEY_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.regionsSynchronized").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/jobs/seed-dimensions should return HTTP 200 when date range is valid")
    void seedDimensions_whenValidRange_shouldReturnHttp200() throws Exception {
        mockMvc.perform(post(SEED_DIMENSIONS_URL)
                        .header(API_KEY_HEADER, API_KEY_VALUE)
                        .param("startYear", "2026")
                        .param("endYear", "2027"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datesSeeded").isNumber())
                .andExpect(jsonPath("$.timesSeeded").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/jobs/seed-dimensions should return HTTP 400 when startYear > endYear")
    void seedDimensions_whenInvalidRange_shouldReturnHttp400() throws Exception {
        mockMvc.perform(post(SEED_DIMENSIONS_URL)
                        .header(API_KEY_HEADER, API_KEY_VALUE)
                        .param("startYear", "2030")
                        .param("endYear", "2025"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/jobs/sde-sync should dispatch asynchronously and return HTTP 202 Accepted")
    void triggerSdeSync_shouldReturnHttp202() throws Exception {
        mockMvc.perform(post(SDE_SYNC_URL).header(API_KEY_HEADER, API_KEY_VALUE))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("sdeSyncJob"))
                .andExpect(jsonPath("$.jobExecutionId").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/jobs/daily-summary should dispatch asynchronously and return HTTP 202 Accepted")
    void triggerDailySummary_whenValidDate_shouldReturnHttp202() throws Exception {
        mockMvc.perform(post(DAILY_SUMMARY_URL)
                        .header(API_KEY_HEADER, API_KEY_VALUE)
                        .param("targetDate", "2026-04-12"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("dailyMarketSummaryJob"))
                .andExpect(jsonPath("$.jobExecutionId").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/jobs/daily-summary should return HTTP 400 when date format is invalid")
    void triggerDailySummary_whenInvalidDate_shouldReturnHttp400() throws Exception {
        mockMvc.perform(post(DAILY_SUMMARY_URL)
                        .header(API_KEY_HEADER, API_KEY_VALUE)
                        .param("targetDate", "not-a-valid-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/jobs/liquidity should dispatch asynchronously and return HTTP 202 Accepted")
    void triggerLiquidity_whenValidInstant_shouldReturnHttp202() throws Exception {
        mockMvc.perform(post(LIQUIDITY_URL)
                        .header(API_KEY_HEADER, API_KEY_VALUE)
                        .param("snapshotTime", "2026-04-12T12:00:00Z"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("orderBookLiquidityJob"))
                .andExpect(jsonPath("$.jobExecutionId").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/jobs/liquidity should return HTTP 400 when timestamp format is invalid")
    void triggerLiquidity_whenInvalidInstant_shouldReturnHttp400() throws Exception {
        mockMvc.perform(post(LIQUIDITY_URL)
                        .header(API_KEY_HEADER, API_KEY_VALUE)
                        .param("snapshotTime", "not-an-iso-instant"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/jobs/executions/{id} should return HTTP 404 when job does not exist")
    void getExecutionDetail_whenMissing_shouldReturnHttp404() throws Exception {
        mockMvc.perform(get(EXECUTIONS_URL + "9999999").header(API_KEY_HEADER, API_KEY_VALUE))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/jobs/executions/{id} should return HTTP 200 for existing execution")
    void getExecutionDetail_whenPresent_shouldReturnHttp200() throws Exception {
        // First launch a job to create an execution
        MvcResult launchResult = mockMvc.perform(post(DAILY_SUMMARY_URL)
                        .header(API_KEY_HEADER, API_KEY_VALUE)
                        .param("targetDate", "2026-01-01"))
                .andExpect(status().isAccepted())
                .andReturn();

        String responseContent = launchResult.getResponse().getContentAsString();
        JobLaunchResponseDto launchDto = jsonMapper.readValue(responseContent, JobLaunchResponseDto.class);
        long executionId = launchDto.jobExecutionId();
        assertThat(executionId).isPositive();

        mockMvc.perform(get(EXECUTIONS_URL + executionId).header(API_KEY_HEADER, API_KEY_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobExecutionId").value(executionId))
                .andExpect(jsonPath("$.jobName").value("dailyMarketSummaryJob"))
                .andExpect(jsonPath("$.podName").value("test-pod"))
                .andExpect(jsonPath("$.podNodeName").value("test-node"));
    }

    @Test
    @DisplayName("POST /api/v1/jobs/sde-sync with custom URLs should dispatch asynchronously")
    void triggerSdeSync_withCustomUrls_shouldReturnHttp202() throws Exception {
        mockMvc.perform(post(SDE_SYNC_URL)
                        .header(API_KEY_HEADER, API_KEY_VALUE)
                        .param("itemsUrl", "classpath:sde/items_test.csv")
                        .param("stationsUrl", "classpath:sde/stations_test.csv"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("sdeSyncJob"));
    }

    @Test
    @DisplayName("POST /api/v1/jobs/daily-summary without date should default to today UTC and return HTTP 202")
    void triggerDailySummary_withoutDate_shouldReturnHttp202() throws Exception {
        mockMvc.perform(post(DAILY_SUMMARY_URL).header(API_KEY_HEADER, API_KEY_VALUE))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("dailyMarketSummaryJob"));
    }

    @Test
    @DisplayName("POST /api/v1/jobs/liquidity without snapshotTime should default to now and return HTTP 202")
    void triggerLiquidity_withoutSnapshotTime_shouldReturnHttp202() throws Exception {
        mockMvc.perform(post(LIQUIDITY_URL).header(API_KEY_HEADER, API_KEY_VALUE))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("orderBookLiquidityJob"));
    }

    @Test
    @DisplayName("Controller should return 500 when job operator fails")
    void controller_whenOperatorThrows_shouldReturn500() throws Exception {
        JobOperator failingOperator = mock(JobOperator.class);
        when(failingOperator.start(any(Job.class), any(JobParameters.class)))
                .thenThrow(new RuntimeException("Simulated launch failure"));

        JobRepository mockJobRepository = mock(JobRepository.class);
        Job job = mock(Job.class);
        when(job.getName()).thenReturn("testJob");
        EsiRegionSyncService esiRegionSyncService = mock(EsiRegionSyncService.class);
        when(esiRegionSyncService.syncRegions())
                .thenThrow(new IllegalStateException("Failed to synchronize ESI regions"));
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);

        JobExecutionController controller = new JobExecutionController(
                failingOperator, mockJobRepository, job, job, job,
                esiRegionSyncService, dateDimensionService, timeDimensionService,
                createTestJobParametersFactory()
        );

        assertThatThrownBy(() -> controller.triggerSdeSync(null, null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.triggerDailySummary("2026-01-01"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.triggerLiquidity("2026-01-01T12:00:00Z"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(controller::triggerRegionsSync)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to synchronize ESI regions");
    }

    @Test
    @DisplayName("Should reject job triggers with HTTP 409 Conflict when job is already executing")
    void triggerJobs_whenAlreadyRunning_shouldThrowConflict() {
        JobOperator operator = mock(JobOperator.class);
        JobRepository mockJobRepository = mock(JobRepository.class);
        Job sdeJob = mock(Job.class);
        when(sdeJob.getName()).thenReturn(SdeSyncJobConfig.JOB_NAME);
        Job dailyJob = mock(Job.class);
        when(dailyJob.getName()).thenReturn(DailyMarketSummaryJobConfig.JOB_NAME);
        Job liquidityJob = mock(Job.class);
        when(liquidityJob.getName()).thenReturn(OrderBookLiquidityJobConfig.JOB_NAME);

        when(mockJobRepository.findRunningJobExecutions(SdeSyncJobConfig.JOB_NAME))
                .thenReturn(Set.of(new JobExecution(101L, new JobInstance(1L, SdeSyncJobConfig.JOB_NAME), new JobParameters())));
        when(mockJobRepository.findRunningJobExecutions(DailyMarketSummaryJobConfig.JOB_NAME))
                .thenReturn(Set.of(new JobExecution(102L, new JobInstance(2L, DailyMarketSummaryJobConfig.JOB_NAME), new JobParameters())));
        when(mockJobRepository.findRunningJobExecutions(OrderBookLiquidityJobConfig.JOB_NAME))
                .thenReturn(Set.of(new JobExecution(103L, new JobInstance(3L, OrderBookLiquidityJobConfig.JOB_NAME), new JobParameters())));

        EsiRegionSyncService esiRegionSyncService = mock(EsiRegionSyncService.class);
        when(esiRegionSyncService.syncRegions())
                .thenThrow(new IllegalStateException("ESI region synchronization is already in progress"));

        JobExecutionController controller = new JobExecutionController(
                operator, mockJobRepository, sdeJob, dailyJob, liquidityJob,
                esiRegionSyncService, mock(DateDimensionService.class), mock(TimeDimensionService.class),
                createTestJobParametersFactory()
        );

        assertThatThrownBy(() -> controller.triggerSdeSync(null, null))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode().value() == 409)
                .hasMessageContaining("sdeSyncJob");

        assertThatThrownBy(() -> controller.triggerDailySummary("2026-01-01"))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode().value() == 409)
                .hasMessageContaining("dailyMarketSummaryJob");

        assertThatThrownBy(() -> controller.triggerLiquidity("2026-01-01T12:00:00Z"))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode().value() == 409)
                .hasMessageContaining("orderBookLiquidityJob");

        assertThatThrownBy(controller::triggerRegionsSync)
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode().value() == 409)
                .hasMessageContaining("already in progress");
    }

    @Test
    @DisplayName("Should map JobExecutionAlreadyRunningException to HTTP 409 Conflict")
    void triggerJobs_whenAlreadyRunningException_shouldThrowConflict() throws Exception {
        JobOperator operator = mock(JobOperator.class);
        when(operator.start(any(Job.class), any(JobParameters.class)))
                .thenThrow(new JobExecutionAlreadyRunningException("Already running"));

        JobRepository mockJobRepository = mock(JobRepository.class);
        Job job = mock(Job.class);
        when(job.getName()).thenReturn("testJob");

        JobExecutionController controller = new JobExecutionController(
                operator, mockJobRepository, job, job, job,
                mock(EsiRegionSyncService.class), mock(DateDimensionService.class), mock(TimeDimensionService.class),
                createTestJobParametersFactory()
        );

        assertThatThrownBy(() -> controller.triggerSdeSync(null, null))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode().value() == 409)
                .hasMessageContaining("testJob");
    }

    private static BatchJobParametersFactory createTestJobParametersFactory() {
        com.github.serbentd.eve.batch.config.BatchProperties properties = mock(com.github.serbentd.eve.batch.config.BatchProperties.class);
        com.github.serbentd.eve.batch.config.BatchProperties.KubernetesProperties k8sProps =
                new com.github.serbentd.eve.batch.config.BatchProperties.KubernetesProperties(
                        "test-pod", "test-node", com.github.serbentd.eve.batch.config.BatchExecutionMode.DAEMON, false);
        when(properties.kubernetes()).thenReturn(k8sProps);
        return new BatchJobParametersFactory(properties);
    }

    @Test
    @DisplayName("Concurrent liquidity triggers should result in exactly one 202 and all others 409")
    void triggerLiquidity_concurrentCalls_shouldReturn202And409() throws Exception {
        int threadCount = 5;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return mockMvc.perform(post(LIQUIDITY_URL).header(API_KEY_HEADER, API_KEY_VALUE))
                        .andReturn().getResponse().getStatus();
            }));
        }

        startLatch.countDown();
        java.util.List<Integer> statuses = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<Integer> future : futures) {
            statuses.add(future.get());
        }
        executor.shutdown();

        assertThat(statuses).contains(202);
        assertThat(statuses).containsOnly(202, 409);
        long count202 = statuses.stream().filter(s -> s == 202).count();
        assertThat(count202).isEqualTo(1);
    }
}
