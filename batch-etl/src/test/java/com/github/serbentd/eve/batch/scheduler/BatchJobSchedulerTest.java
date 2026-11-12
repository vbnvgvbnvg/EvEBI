package com.github.serbentd.eve.batch.scheduler;

import com.github.serbentd.eve.batch.config.BatchProperties;
import com.github.serbentd.eve.batch.config.PartitionRole;
import com.github.serbentd.eve.batch.service.DateDimensionService;
import com.github.serbentd.eve.batch.service.TimeDimensionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

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
        JobLauncher jobLauncher = mock(JobLauncher.class);
        Job sdeSyncJob = mock(Job.class);
        Job dailyMarketSummaryJob = mock(Job.class);
        Job orderBookLiquidityJob = mock(Job.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);

        when(jobLauncher.run(any(Job.class), any(JobParameters.class))).thenReturn(new JobExecution(1L));

        BatchProperties properties = createProperties(PartitionRole.STANDALONE);

        BatchJobScheduler scheduler = new BatchJobScheduler(
                jobLauncher,
                sdeSyncJob,
                dailyMarketSummaryJob,
                orderBookLiquidityJob,
                properties,
                dateDimensionService,
                timeDimensionService
        );

        // 1. Startup dimension seeding
        scheduler.seedDimensionsOnStartup();
        verify(dateDimensionService).seedDateDimension(LocalDate.of(2020, 1, 1), LocalDate.of(2035, 12, 31));
        verify(timeDimensionService).seedTimeDimension();

        // 2. Scheduled triggers
        scheduler.scheduleSdeSync();
        verify(jobLauncher).run(eq(sdeSyncJob), any(JobParameters.class));

        scheduler.scheduleDailySummary();
        verify(jobLauncher).run(eq(dailyMarketSummaryJob), any(JobParameters.class));

        scheduler.scheduleLiquiditySnapshot();
        verify(jobLauncher).run(eq(orderBookLiquidityJob), any(JobParameters.class));
    }

    @Test
    @DisplayName("Should skip all scheduled triggers and startup seeding when node role is WORKER")
    void scheduler_whenWorkerRole_shouldSkipExecution() throws Exception {
        JobLauncher jobLauncher = mock(JobLauncher.class);
        Job sdeSyncJob = mock(Job.class);
        Job dailyMarketSummaryJob = mock(Job.class);
        Job orderBookLiquidityJob = mock(Job.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);

        BatchProperties properties = createProperties(PartitionRole.WORKER);

        BatchJobScheduler scheduler = new BatchJobScheduler(
                jobLauncher,
                sdeSyncJob,
                dailyMarketSummaryJob,
                orderBookLiquidityJob,
                properties,
                dateDimensionService,
                timeDimensionService
        );

        scheduler.seedDimensionsOnStartup();
        verify(dateDimensionService, never()).seedDateDimension(any(), any());
        verify(timeDimensionService, never()).seedTimeDimension();

        scheduler.scheduleSdeSync();
        verify(jobLauncher, never()).run(eq(sdeSyncJob), any());

        scheduler.scheduleDailySummary();
        verify(jobLauncher, never()).run(eq(dailyMarketSummaryJob), any());

        scheduler.scheduleLiquiditySnapshot();
        verify(jobLauncher, never()).run(eq(orderBookLiquidityJob), any());
    }

    @Test
    @DisplayName("Should catch and log exceptions during scheduled runs without propagating")
    void scheduler_whenJobLaunchThrows_shouldCatchGracefully() throws Exception {
        JobLauncher jobLauncher = mock(JobLauncher.class);
        Job sdeSyncJob = mock(Job.class);
        Job dailyMarketSummaryJob = mock(Job.class);
        Job orderBookLiquidityJob = mock(Job.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);

        when(jobLauncher.run(any(Job.class), any(JobParameters.class)))
                .thenThrow(new RuntimeException("Simulated job launch failure"));

        BatchProperties properties = createProperties(PartitionRole.STANDALONE);

        BatchJobScheduler scheduler = new BatchJobScheduler(
                jobLauncher,
                sdeSyncJob,
                dailyMarketSummaryJob,
                orderBookLiquidityJob,
                properties,
                dateDimensionService,
                timeDimensionService
        );

        // Verify none of these throw exceptions when the underlying job launcher fails
        assertDoesNotThrow(scheduler::scheduleSdeSync);
        assertDoesNotThrow(scheduler::scheduleDailySummary);
        assertDoesNotThrow(scheduler::scheduleLiquiditySnapshot);

        verify(jobLauncher).run(eq(sdeSyncJob), any(JobParameters.class));
        verify(jobLauncher).run(eq(dailyMarketSummaryJob), any(JobParameters.class));
        verify(jobLauncher).run(eq(orderBookLiquidityJob), any(JobParameters.class));
    }

    private static BatchProperties createProperties(PartitionRole role) {
        return new BatchProperties(
                new BatchProperties.ChunkSizeProperties(500, 1000),
                new BatchProperties.AsyncLauncherProperties(2, 4, 10),
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
                )
        );
    }
}
