package com.github.serbentd.eve.batch.scheduler;

import com.github.serbentd.eve.batch.config.BatchExecutionMode;
import com.github.serbentd.eve.batch.config.BatchJobParametersFactory;
import com.github.serbentd.eve.batch.config.BatchProperties;
import com.github.serbentd.eve.batch.config.PartitionRole;
import com.github.serbentd.eve.batch.job.daily.DailyMarketSummaryJobConfig;
import com.github.serbentd.eve.batch.job.liquidity.OrderBookLiquidityJobConfig;
import com.github.serbentd.eve.batch.service.DateDimensionService;
import com.github.serbentd.eve.batch.service.TimeDimensionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubernetesCronJobRunnerTest {

    private static final long THE_FORGE_REGION_ID = 10000002L;

    @Test
    @DisplayName("Should do nothing and exit code 0 when execution mode is DAEMON")
    void run_whenDaemonMode_shouldDoNothing() throws Exception {
        JobLauncher jobLauncher = mock(JobLauncher.class);
        Job sdeJob = mock(Job.class);
        Job dailyJob = mock(Job.class);
        Job liquidityJob = mock(Job.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);
        ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);

        BatchProperties properties = createProperties(BatchExecutionMode.DAEMON, false);
        BatchJobParametersFactory factory = new BatchJobParametersFactory(properties);

        KubernetesCronJobRunner runner = new KubernetesCronJobRunner(
                jobLauncher, sdeJob, dailyJob, liquidityJob,
                properties, factory, dateDimensionService, timeDimensionService, applicationContext
        );

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isZero();
        verify(jobLauncher, never()).run(any(), any());
        verify(dateDimensionService, never()).seedDateDimension(any(), any());
    }

    @Test
    @DisplayName("Should execute SDE sync job synchronously and exit 0 when execution mode is SDE_SYNC")
    void run_whenSdeSyncMode_shouldExecuteSdeJob() throws Exception {
        JobLauncher jobLauncher = mock(JobLauncher.class);
        Job sdeJob = mock(Job.class);
        Job dailyJob = mock(Job.class);
        Job liquidityJob = mock(Job.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);
        ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);

        JobExecution execution = new JobExecution(101L);
        execution.setStatus(BatchStatus.COMPLETED);
        when(jobLauncher.run(eq(sdeJob), any())).thenReturn(execution);

        BatchProperties properties = createProperties(BatchExecutionMode.SDE_SYNC, false);
        BatchJobParametersFactory factory = new BatchJobParametersFactory(properties);

        KubernetesCronJobRunner runner = new KubernetesCronJobRunner(
                jobLauncher, sdeJob, dailyJob, liquidityJob,
                properties, factory, dateDimensionService, timeDimensionService, applicationContext
        );

        runner.run(new DefaultApplicationArguments("--itemsUrl=http://custom/items.csv", "--stationsUrl=http://custom/stations.csv"));

        assertThat(runner.getExitCode()).isZero();
        verify(dateDimensionService).seedDateDimension(LocalDate.of(2020, 1, 1), LocalDate.of(2035, 12, 31));
        verify(timeDimensionService).seedTimeDimension();

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(sdeJob), captor.capture());
        JobParameters params = captor.getValue();
        assertThat(params.getString("itemsUrl")).isEqualTo("http://custom/items.csv");
        assertThat(params.getString("stationsUrl")).isEqualTo("http://custom/stations.csv");
        assertThat(params.getString(BatchJobParametersFactory.POD_NAME_PARAM)).isEqualTo("k8s-pod-1");
    }

    @Test
    @DisplayName("Should default targetDate to yesterday UTC for DAILY_MARKET_SUMMARY when not specified in CLI args")
    void run_whenDailySummary_shouldDefaultToYesterdayUtc() throws Exception {
        JobLauncher jobLauncher = mock(JobLauncher.class);
        Job sdeJob = mock(Job.class);
        Job dailyJob = mock(Job.class);
        Job liquidityJob = mock(Job.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);
        ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);

        JobExecution execution = new JobExecution(102L);
        execution.setStatus(BatchStatus.COMPLETED);
        when(jobLauncher.run(eq(dailyJob), any())).thenReturn(execution);

        BatchProperties properties = createProperties(BatchExecutionMode.DAILY_MARKET_SUMMARY, false);
        BatchJobParametersFactory factory = new BatchJobParametersFactory(properties);

        KubernetesCronJobRunner runner = new KubernetesCronJobRunner(
                jobLauncher, sdeJob, dailyJob, liquidityJob,
                properties, factory, dateDimensionService, timeDimensionService, applicationContext
        );

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isZero();
        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(dailyJob), captor.capture());
        JobParameters params = captor.getValue();
        LocalDate expectedYesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        assertThat(params.getString(DailyMarketSummaryJobConfig.TARGET_DATE_PARAM)).isEqualTo(expectedYesterday.toString());
    }

    @Test
    @DisplayName("Should honor --targetDate CLI override for DAILY_MARKET_SUMMARY")
    void run_whenDailySummary_shouldRespectCliTargetDate() throws Exception {
        JobLauncher jobLauncher = mock(JobLauncher.class);
        Job sdeJob = mock(Job.class);
        Job dailyJob = mock(Job.class);
        Job liquidityJob = mock(Job.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);
        ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);

        JobExecution execution = new JobExecution(103L);
        execution.setStatus(BatchStatus.COMPLETED);
        when(jobLauncher.run(eq(dailyJob), any())).thenReturn(execution);

        BatchProperties properties = createProperties(BatchExecutionMode.DAILY_MARKET_SUMMARY, false);
        BatchJobParametersFactory factory = new BatchJobParametersFactory(properties);

        KubernetesCronJobRunner runner = new KubernetesCronJobRunner(
                jobLauncher, sdeJob, dailyJob, liquidityJob,
                properties, factory, dateDimensionService, timeDimensionService, applicationContext
        );

        runner.run(new DefaultApplicationArguments("--targetDate=2026-05-20"));

        assertThat(runner.getExitCode()).isZero();
        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(dailyJob), captor.capture());
        assertThat(captor.getValue().getString(DailyMarketSummaryJobConfig.TARGET_DATE_PARAM)).isEqualTo("2026-05-20");
    }

    @Test
    @DisplayName("Should honor --snapshotTime CLI override for ORDER_BOOK_LIQUIDITY")
    void run_whenLiquidity_shouldRespectCliSnapshotTime() throws Exception {
        JobLauncher jobLauncher = mock(JobLauncher.class);
        Job sdeJob = mock(Job.class);
        Job dailyJob = mock(Job.class);
        Job liquidityJob = mock(Job.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);
        ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);

        JobExecution execution = new JobExecution(104L);
        execution.setStatus(BatchStatus.COMPLETED);
        when(jobLauncher.run(eq(liquidityJob), any())).thenReturn(execution);

        BatchProperties properties = createProperties(BatchExecutionMode.ORDER_BOOK_LIQUIDITY, false);
        BatchJobParametersFactory factory = new BatchJobParametersFactory(properties);

        KubernetesCronJobRunner runner = new KubernetesCronJobRunner(
                jobLauncher, sdeJob, dailyJob, liquidityJob,
                properties, factory, dateDimensionService, timeDimensionService, applicationContext
        );

        runner.run(new DefaultApplicationArguments("--snapshotTime=2026-05-20T14:30:00Z"));

        assertThat(runner.getExitCode()).isZero();
        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(liquidityJob), captor.capture());
        assertThat(captor.getValue().getString(OrderBookLiquidityJobConfig.SNAPSHOT_TIME_PARAM)).isEqualTo("2026-05-20T14:30:00Z");
    }

    @Test
    @DisplayName("Should set exitCode 1 when batch execution finishes in FAILED status")
    void run_whenJobFails_shouldSetExitCode1() throws Exception {
        JobLauncher jobLauncher = mock(JobLauncher.class);
        Job sdeJob = mock(Job.class);
        Job dailyJob = mock(Job.class);
        Job liquidityJob = mock(Job.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);
        ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);

        JobExecution execution = new JobExecution(105L);
        execution.setStatus(BatchStatus.FAILED);
        when(jobLauncher.run(eq(sdeJob), any())).thenReturn(execution);

        BatchProperties properties = createProperties(BatchExecutionMode.SDE_SYNC, false);
        BatchJobParametersFactory factory = new BatchJobParametersFactory(properties);

        KubernetesCronJobRunner runner = new KubernetesCronJobRunner(
                jobLauncher, sdeJob, dailyJob, liquidityJob,
                properties, factory, dateDimensionService, timeDimensionService, applicationContext
        );

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should set exitCode 1 when job launcher throws an unhandled exception")
    void run_whenJobThrows_shouldSetExitCode1() throws Exception {
        JobLauncher jobLauncher = mock(JobLauncher.class);
        Job sdeJob = mock(Job.class);
        Job dailyJob = mock(Job.class);
        Job liquidityJob = mock(Job.class);
        DateDimensionService dateDimensionService = mock(DateDimensionService.class);
        TimeDimensionService timeDimensionService = mock(TimeDimensionService.class);
        ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);

        when(jobLauncher.run(any(), any())).thenThrow(new RuntimeException("Simulated launcher failure"));

        BatchProperties properties = createProperties(BatchExecutionMode.DAILY_MARKET_SUMMARY, false);
        BatchJobParametersFactory factory = new BatchJobParametersFactory(properties);

        KubernetesCronJobRunner runner = new KubernetesCronJobRunner(
                jobLauncher, sdeJob, dailyJob, liquidityJob,
                properties, factory, dateDimensionService, timeDimensionService, applicationContext
        );

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    private static BatchProperties createProperties(BatchExecutionMode mode, boolean exitOnCompletion) {
        return new BatchProperties(
                new BatchProperties.ChunkSizeProperties(500, 1000),
                new BatchProperties.AsyncLauncherProperties(2, 4, 10),
                new BatchProperties.SdeProperties("http://localhost", "http://localhost", "Test", Duration.ofSeconds(5), Duration.ofSeconds(5), 100),
                new BatchProperties.EsiProperties("http://localhost", "http://localhost", "Test", 10000000L, 11000000L, Duration.ofSeconds(5), Duration.ofSeconds(5)),
                new BatchProperties.ScheduleProperties(true, "0 0 3 * * SUN", "0 15 0 * * *", "0 0/15 * * * *"),
                new BatchProperties.RemotePartitioningProperties(
                        PartitionRole.STANDALONE,
                        true,
                        "ex", "req", "req", "dlx", "dlq", "dlq",
                        Duration.ofSeconds(1), Duration.ofSeconds(10), 2, 1,
                        Map.of(THE_FORGE_REGION_ID, 2)
                ),
                new BatchProperties.KubernetesProperties("k8s-pod-1", "k8s-node-1", mode, exitOnCompletion)
        );
    }
}
