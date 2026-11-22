package com.github.serbentd.eve.batch.scheduler;

import com.github.serbentd.eve.batch.config.BatchExecutionMode;
import com.github.serbentd.eve.batch.config.BatchJobParametersFactory;
import com.github.serbentd.eve.batch.config.BatchProperties;
import com.github.serbentd.eve.batch.config.PartitionRole;
import com.github.serbentd.eve.batch.service.DateDimensionService;
import com.github.serbentd.eve.batch.service.TimeDimensionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchJobSchedulerTest {

    private static final long THE_FORGE_REGION_ID = 10000002L;
    private static final long MIN_MARKET_REGION_ID = 10000000L;
    private static final long MAX_MARKET_REGION_ID = 11000000L;

    @Test
    @DisplayName("Should seed temporal dimensions and trigger scheduled jobs on STANDALONE node")
    void scheduler_whenStandaloneRole_shouldExecuteAllTriggers() throws Exception {
        JobOperator jobOperator = mock(JobOperator.class);
        Job sdeSyncJob = mock(Job.class);
        Job dailyMarketSummaryJob = mock(Job.class);
        Job orderBookLiquidityJob = mock(Job.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);

        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(new JobExecution(1L, new JobInstance(1L, "testJob"), new JobParameters()));

        BatchProperties properties = createProperties(PartitionRole.STANDALONE);

        BatchJobScheduler scheduler = new BatchJobScheduler(
                jobOperator,
                sdeSyncJob,
                dailyMarketSummaryJob,
                orderBookLiquidityJob,
                properties,
                dateDimensionService,
                timeDimensionService,
                new BatchJobParametersFactory(properties)
        );

        // 1. Startup dimension seeding
        scheduler.seedDimensionsOnStartup();
        verify(dateDimensionService).seedDateDimension(LocalDate.of(2020, 1, 1), LocalDate.of(2035, 12, 31));
        verify(timeDimensionService).seedTimeDimension();

        // 2. Scheduled triggers
        ArgumentCaptor<JobParameters> paramsCaptor = ArgumentCaptor.forClass(JobParameters.class);

        scheduler.scheduleSdeSync();
        verify(jobOperator).start(eq(sdeSyncJob), paramsCaptor.capture());
        JobParameters sdeParams = paramsCaptor.getValue();
        assertThat(sdeParams.getString(BatchJobParametersFactory.POD_NAME_PARAM)).isEqualTo("test-pod");
        assertThat(sdeParams.getString(BatchJobParametersFactory.POD_NODE_NAME_PARAM)).isEqualTo("test-node");
        assertThat(sdeParams.getParameter(BatchJobParametersFactory.POD_NAME_PARAM).identifying()).isFalse();
        assertThat(sdeParams.getParameter(BatchJobParametersFactory.POD_NODE_NAME_PARAM).identifying()).isFalse();
        assertThat(sdeParams.getParameter(BatchJobParametersFactory.TIMESTAMP_PARAM).identifying()).isFalse();
        assertThat(sdeParams.getParameter("targetDate").identifying()).isTrue();

        scheduler.scheduleDailySummary();
        verify(jobOperator).start(eq(dailyMarketSummaryJob), paramsCaptor.capture());
        JobParameters dailyParams = paramsCaptor.getValue();
        assertThat(dailyParams.getString(BatchJobParametersFactory.POD_NAME_PARAM)).isEqualTo("test-pod");
        assertThat(dailyParams.getString(BatchJobParametersFactory.POD_NODE_NAME_PARAM)).isEqualTo("test-node");
        assertThat(dailyParams.getParameter(BatchJobParametersFactory.POD_NAME_PARAM).identifying()).isFalse();
        assertThat(dailyParams.getParameter(BatchJobParametersFactory.TIMESTAMP_PARAM).identifying()).isFalse();
        assertThat(dailyParams.getParameter("targetDate").identifying()).isTrue();

        scheduler.scheduleLiquiditySnapshot();
        verify(jobOperator).start(eq(orderBookLiquidityJob), paramsCaptor.capture());
        JobParameters liqParams = paramsCaptor.getValue();
        assertThat(liqParams.getString(BatchJobParametersFactory.POD_NAME_PARAM)).isEqualTo("test-pod");
        assertThat(liqParams.getString(BatchJobParametersFactory.POD_NODE_NAME_PARAM)).isEqualTo("test-node");
        assertThat(liqParams.getParameter(BatchJobParametersFactory.POD_NAME_PARAM).identifying()).isFalse();
        assertThat(liqParams.getParameter(BatchJobParametersFactory.TIMESTAMP_PARAM).identifying()).isFalse();
        assertThat(liqParams.getParameter("snapshotTime").identifying()).isTrue();
    }

    @Test
    @DisplayName("Should skip all scheduled triggers and startup seeding when node role is WORKER")
    void scheduler_whenWorkerRole_shouldSkipExecution() throws Exception {
        JobOperator jobOperator = mock(JobOperator.class);
        Job sdeSyncJob = mock(Job.class);
        Job dailyMarketSummaryJob = mock(Job.class);
        Job orderBookLiquidityJob = mock(Job.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);

        BatchProperties properties = createProperties(PartitionRole.WORKER);

        BatchJobScheduler scheduler = new BatchJobScheduler(
                jobOperator,
                sdeSyncJob,
                dailyMarketSummaryJob,
                orderBookLiquidityJob,
                properties,
                dateDimensionService,
                timeDimensionService,
                new BatchJobParametersFactory(properties)
        );

        scheduler.seedDimensionsOnStartup();
        verify(dateDimensionService, never()).seedDateDimension(any(), any());
        verify(timeDimensionService, never()).seedTimeDimension();

        scheduler.scheduleSdeSync();
        verify(jobOperator, never()).start(eq(sdeSyncJob), any());

        scheduler.scheduleDailySummary();
        verify(jobOperator, never()).start(eq(dailyMarketSummaryJob), any());

        scheduler.scheduleLiquiditySnapshot();
        verify(jobOperator, never()).start(eq(orderBookLiquidityJob), any());
    }

    @Test
    @DisplayName("Should skip all scheduled triggers and startup seeding when node is running in one-shot mode")
    void scheduler_whenOneShotMode_shouldSkipExecution() throws Exception {
        JobOperator jobOperator = mock(JobOperator.class);
        Job sdeSyncJob = mock(Job.class);
        Job dailyMarketSummaryJob = mock(Job.class);
        Job orderBookLiquidityJob = mock(Job.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);

        BatchProperties properties = new BatchProperties(
                new BatchProperties.ChunkSizeProperties(500, 1000),
                new BatchProperties.AsyncLauncherProperties(2, 4, 10, Duration.ofSeconds(60)),
                new BatchProperties.SdeProperties("http://localhost", "http://localhost", "Test", Duration.ofSeconds(5), Duration.ofSeconds(5), 100),
                new BatchProperties.EsiProperties("http://localhost", "http://localhost", "Test", MIN_MARKET_REGION_ID, MAX_MARKET_REGION_ID, Duration.ofSeconds(5), Duration.ofSeconds(5)),
                new BatchProperties.ScheduleProperties(true, "0 0 3 * * SUN", "0 15 0 * * *", "0 0/15 * * * *"),
                new BatchProperties.RemotePartitioningProperties(
                        PartitionRole.STANDALONE, true, "ex", "req", "req", "dlx", "dlq", "dlq",
                        Duration.ofSeconds(1), Duration.ofSeconds(10), 2, 1, Map.of(THE_FORGE_REGION_ID, 2)
                ),
                new BatchProperties.KubernetesProperties("test-pod", "test-node", BatchExecutionMode.DAILY_MARKET_SUMMARY, false)
        );

        BatchJobScheduler scheduler = new BatchJobScheduler(
                jobOperator,
                sdeSyncJob,
                dailyMarketSummaryJob,
                orderBookLiquidityJob,
                properties,
                dateDimensionService,
                timeDimensionService,
                new BatchJobParametersFactory(properties)
        );

        scheduler.seedDimensionsOnStartup();
        verify(dateDimensionService, never()).seedDateDimension(any(), any());
        verify(timeDimensionService, never()).seedTimeDimension();

        scheduler.scheduleSdeSync();
        verify(jobOperator, never()).start(eq(sdeSyncJob), any());

        scheduler.scheduleDailySummary();
        verify(jobOperator, never()).start(eq(dailyMarketSummaryJob), any());

        scheduler.scheduleLiquiditySnapshot();
        verify(jobOperator, never()).start(eq(orderBookLiquidityJob), any());
    }

    @Test
    @DisplayName("Should catch and log exceptions during scheduled runs without propagating")
    void scheduler_whenJobLaunchThrows_shouldCatchGracefully() throws Exception {
        JobOperator jobOperator = mock(JobOperator.class);
        Job sdeSyncJob = mock(Job.class);
        Job dailyMarketSummaryJob = mock(Job.class);
        Job orderBookLiquidityJob = mock(Job.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);

        when(jobOperator.start(any(Job.class), any(JobParameters.class)))
                .thenThrow(new RuntimeException("Simulated job launch failure"));

        BatchProperties properties = createProperties(PartitionRole.STANDALONE);

        BatchJobScheduler scheduler = new BatchJobScheduler(
                jobOperator,
                sdeSyncJob,
                dailyMarketSummaryJob,
                orderBookLiquidityJob,
                properties,
                dateDimensionService,
                timeDimensionService,
                new BatchJobParametersFactory(properties)
        );

        // Verify none of these throw exceptions when the underlying job operator fails
        assertDoesNotThrow(scheduler::scheduleSdeSync);
        assertDoesNotThrow(scheduler::scheduleDailySummary);
        assertDoesNotThrow(scheduler::scheduleLiquiditySnapshot);

        verify(jobOperator).start(eq(sdeSyncJob), any(JobParameters.class));
        verify(jobOperator).start(eq(dailyMarketSummaryJob), any(JobParameters.class));
        verify(jobOperator).start(eq(orderBookLiquidityJob), any(JobParameters.class));
    }

    private static BatchProperties createProperties(PartitionRole role) {
        return new BatchProperties(
                new BatchProperties.ChunkSizeProperties(500, 1000),
                new BatchProperties.AsyncLauncherProperties(2, 4, 10, Duration.ofSeconds(60)),
                new BatchProperties.SdeProperties("http://localhost", "http://localhost", "Test", Duration.ofSeconds(5), Duration.ofSeconds(5), 100),
                new BatchProperties.EsiProperties("http://localhost", "http://localhost", "Test", MIN_MARKET_REGION_ID, MAX_MARKET_REGION_ID, Duration.ofSeconds(5), Duration.ofSeconds(5)),
                new BatchProperties.ScheduleProperties(true, "0 0 3 * * SUN", "0 15 0 * * *", "0 0/15 * * * *"),
                new BatchProperties.RemotePartitioningProperties(
                        role,
                        true,
                        "ex",
                        "req",
                        "req",
                        "dlx",
                        "dlq",
                        "dlq",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(10),
                        2,
                        1,
                        Map.of(THE_FORGE_REGION_ID, 2)
                ),
                new BatchProperties.KubernetesProperties("test-pod", "test-node", BatchExecutionMode.DAEMON, false)
        );
    }
}
