package com.github.serbentd.eve.batch.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

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

    private final MockMvc mockMvc;
    private final JobExplorer jobExplorer;
    private final ObjectMapper objectMapper;

    JobExecutionControllerTest(MockMvc mockMvc, JobExplorer jobExplorer, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.jobExplorer = jobExplorer;
        this.objectMapper = objectMapper;
    }

    @AfterEach
    void awaitRunningJobs() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Set<JobExecution> runningSde = jobExplorer.findRunningJobExecutions(SdeSyncJobConfig.JOB_NAME);
            Set<JobExecution> runningDaily = jobExplorer.findRunningJobExecutions(DailyMarketSummaryJobConfig.JOB_NAME);
            Set<JobExecution> runningLiq = jobExplorer.findRunningJobExecutions(OrderBookLiquidityJobConfig.JOB_NAME);
            if (runningSde.isEmpty() && runningDaily.isEmpty() && runningLiq.isEmpty()) {
                break;
            }
            Thread.sleep(25);
        }
    }

    @Test
    @DisplayName("POST /api/v1/jobs/regions-sync should return HTTP 200 and success status")
    void triggerRegionsSync_shouldReturnHttp200() throws Exception {
        mockMvc.perform(post(REGIONS_SYNC_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.regionsSynchronized").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/jobs/seed-dimensions should return HTTP 200 when date range is valid")
    void seedDimensions_whenValidRange_shouldReturnHttp200() throws Exception {
        mockMvc.perform(post(SEED_DIMENSIONS_URL)
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
                        .param("startYear", "2030")
                        .param("endYear", "2025"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/jobs/sde-sync should dispatch asynchronously and return HTTP 202 Accepted")
    void triggerSdeSync_shouldReturnHttp202() throws Exception {
        mockMvc.perform(post(SDE_SYNC_URL))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("sdeSyncJob"))
                .andExpect(jsonPath("$.jobExecutionId").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/jobs/daily-summary should dispatch asynchronously and return HTTP 202 Accepted")
    void triggerDailySummary_whenValidDate_shouldReturnHttp202() throws Exception {
        mockMvc.perform(post(DAILY_SUMMARY_URL)
                        .param("targetDate", "2026-04-12"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("dailyMarketSummaryJob"))
                .andExpect(jsonPath("$.jobExecutionId").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/jobs/daily-summary should return HTTP 400 when date format is invalid")
    void triggerDailySummary_whenInvalidDate_shouldReturnHttp400() throws Exception {
        mockMvc.perform(post(DAILY_SUMMARY_URL)
                        .param("targetDate", "not-a-valid-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/jobs/liquidity should dispatch asynchronously and return HTTP 202 Accepted")
    void triggerLiquidity_whenValidInstant_shouldReturnHttp202() throws Exception {
        mockMvc.perform(post(LIQUIDITY_URL)
                        .param("snapshotTime", "2026-04-12T12:00:00Z"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("orderBookLiquidityJob"))
                .andExpect(jsonPath("$.jobExecutionId").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/jobs/liquidity should return HTTP 400 when timestamp format is invalid")
    void triggerLiquidity_whenInvalidInstant_shouldReturnHttp400() throws Exception {
        mockMvc.perform(post(LIQUIDITY_URL)
                        .param("snapshotTime", "not-an-iso-instant"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/jobs/executions/{id} should return HTTP 404 when job does not exist")
    void getExecutionDetail_whenMissing_shouldReturnHttp404() throws Exception {
        mockMvc.perform(get(EXECUTIONS_URL + "9999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/jobs/executions/{id} should return HTTP 200 for existing execution")
    void getExecutionDetail_whenPresent_shouldReturnHttp200() throws Exception {
        // First launch a job to create an execution
        MvcResult launchResult = mockMvc.perform(post(DAILY_SUMMARY_URL)
                        .param("targetDate", "2026-01-01"))
                .andExpect(status().isAccepted())
                .andReturn();

        String responseContent = launchResult.getResponse().getContentAsString();
        JobLaunchResponseDto launchDto = objectMapper.readValue(responseContent, JobLaunchResponseDto.class);
        long executionId = launchDto.jobExecutionId();
        assertThat(executionId).isPositive();

        mockMvc.perform(get(EXECUTIONS_URL + executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobExecutionId").value(executionId))
                .andExpect(jsonPath("$.jobName").value("dailyMarketSummaryJob"));
    }

    @Test
    @DisplayName("POST /api/v1/jobs/sde-sync with custom URLs should dispatch asynchronously")
    void triggerSdeSync_withCustomUrls_shouldReturnHttp202() throws Exception {
        mockMvc.perform(post(SDE_SYNC_URL)
                        .param("itemsUrl", "classpath:sde/items_test.csv")
                        .param("stationsUrl", "classpath:sde/stations_test.csv"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("sdeSyncJob"));
    }

    @Test
    @DisplayName("POST /api/v1/jobs/daily-summary without date should default to today UTC and return HTTP 202")
    void triggerDailySummary_withoutDate_shouldReturnHttp202() throws Exception {
        mockMvc.perform(post(DAILY_SUMMARY_URL))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("dailyMarketSummaryJob"));
    }

    @Test
    @DisplayName("POST /api/v1/jobs/liquidity without snapshotTime should default to now and return HTTP 202")
    void triggerLiquidity_withoutSnapshotTime_shouldReturnHttp202() throws Exception {
        mockMvc.perform(post(LIQUIDITY_URL))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("orderBookLiquidityJob"));
    }

    @Test
    @DisplayName("Controller should return 500 when job launcher fails")
    void controller_whenLauncherThrows_shouldReturn500() throws Exception {
        JobLauncher failingLauncher = mock(JobLauncher.class);
        when(failingLauncher.run(any(), any()))
                .thenThrow(new RuntimeException("Simulated launch failure"));

        JobExplorer jobExplorer = mock(JobExplorer.class);
        Job job = mock(Job.class);
        EsiRegionSyncService esiRegionSyncService = mock(EsiRegionSyncService.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);

        JobExecutionController controller = new JobExecutionController(
                failingLauncher, jobExplorer, job, job, job,
                esiRegionSyncService, dateDimensionService, timeDimensionService
        );

        assertThatThrownBy(() -> controller.triggerSdeSync(null, null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.triggerDailySummary("2026-01-01"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.triggerLiquidity("2026-01-01T12:00:00Z"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Should reject job triggers with HTTP 409 Conflict when job is already executing")
    void triggerJobs_whenAlreadyRunning_shouldThrowConflict() {
        JobLauncher launcher = mock(JobLauncher.class);
        JobExplorer explorer = mock(JobExplorer.class);
        Job sdeJob = mock(Job.class);
        Job dailyJob = mock(Job.class);
        Job liquidityJob = mock(Job.class);

        when(explorer.findRunningJobExecutions(SdeSyncJobConfig.JOB_NAME))
                .thenReturn(Set.of(new JobExecution(101L)));
        when(explorer.findRunningJobExecutions(DailyMarketSummaryJobConfig.JOB_NAME))
                .thenReturn(Set.of(new JobExecution(102L)));
        when(explorer.findRunningJobExecutions(OrderBookLiquidityJobConfig.JOB_NAME))
                .thenReturn(Set.of(new JobExecution(103L)));

        EsiRegionSyncService esiRegionSyncService = mock(EsiRegionSyncService.class);
        when(esiRegionSyncService.syncRegions())
                .thenThrow(new IllegalStateException("ESI region synchronization is already in progress"));

        JobExecutionController controller = new JobExecutionController(
                launcher, explorer, sdeJob, dailyJob, liquidityJob,
                esiRegionSyncService, mock(DateDimensionService.class), mock(TimeDimensionService.class)
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
}
