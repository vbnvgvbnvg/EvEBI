package com.github.serbentd.eve.batch.scheduler;

import com.github.serbentd.eve.batch.config.BatchExecutionMode;
import com.github.serbentd.eve.batch.config.BatchJobParametersFactory;
import com.github.serbentd.eve.batch.config.BatchProperties;
import com.github.serbentd.eve.batch.job.daily.DailyMarketSummaryJobConfig;
import com.github.serbentd.eve.batch.job.liquidity.OrderBookLiquidityJobConfig;
import com.github.serbentd.eve.batch.job.sde.SdeSyncJobConfig;
import com.github.serbentd.eve.batch.service.DateDimensionService;
import com.github.serbentd.eve.batch.service.TimeDimensionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Startup runner coordinating synchronous one-shot batch job executions for ephemeral
 * Kubernetes CronJob pods.
 * <p>
 * Evaluates {@link BatchExecutionMode}. When set to a one-shot mode ({@code SDE_SYNC},
 * {@code DAILY_MARKET_SUMMARY}, {@code ORDER_BOOK_LIQUIDITY}), seeds conformed calendar/time
 * dimensions, executes the target job synchronously to completion, evaluates terminal status,
 * and signals process completion with a deterministic POSIX exit code (0 on success, 1 on failure).
 */
@Component
@Order(2)
@Slf4j
public class KubernetesCronJobRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final LocalDate DEFAULT_SEED_START_DATE = LocalDate.of(2020, 1, 1);
    private static final LocalDate DEFAULT_SEED_END_DATE = LocalDate.of(2035, 12, 31);

    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_FAILURE = 1;

    private final JobLauncher syncJobLauncher;
    private final Job sdeSyncJob;
    private final Job dailyMarketSummaryJob;
    private final Job orderBookLiquidityJob;
    private final BatchProperties batchProperties;
    private final BatchJobParametersFactory jobParametersFactory;
    private final DateDimensionService dateDimensionService;
    private final TimeDimensionService timeDimensionService;
    private final ConfigurableApplicationContext applicationContext;

    private int exitCode = EXIT_SUCCESS;

    public KubernetesCronJobRunner(
            @Qualifier("jobLauncher") JobLauncher syncJobLauncher,
            @Qualifier(SdeSyncJobConfig.JOB_NAME) Job sdeSyncJob,
            @Qualifier(DailyMarketSummaryJobConfig.JOB_NAME) Job dailyMarketSummaryJob,
            @Qualifier(OrderBookLiquidityJobConfig.JOB_NAME) Job orderBookLiquidityJob,
            BatchProperties batchProperties,
            BatchJobParametersFactory jobParametersFactory,
            DateDimensionService dateDimensionService,
            TimeDimensionService timeDimensionService,
            ConfigurableApplicationContext applicationContext) {
        this.syncJobLauncher = syncJobLauncher;
        this.sdeSyncJob = sdeSyncJob;
        this.dailyMarketSummaryJob = dailyMarketSummaryJob;
        this.orderBookLiquidityJob = orderBookLiquidityJob;
        this.batchProperties = batchProperties;
        this.jobParametersFactory = jobParametersFactory;
        this.dateDimensionService = dateDimensionService;
        this.timeDimensionService = timeDimensionService;
        this.applicationContext = applicationContext;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!batchProperties.kubernetes().isOneShotJob()) {
            log.info("Batch ETL starting in continuous {} mode (pod: {}, node: {})",
                    batchProperties.kubernetes().executionMode(),
                    batchProperties.kubernetes().podName(),
                    batchProperties.kubernetes().podNodeName());
            return;
        }

        BatchExecutionMode mode = batchProperties.kubernetes().executionMode();
        log.info("Starting one-shot Kubernetes batch execution for mode: {} (pod: {}, node: {})",
                mode, batchProperties.kubernetes().podName(), batchProperties.kubernetes().podNodeName());

        try {
            seedTemporalDimensions();
            JobParameters parameters = buildJobParameters(mode, args);
            Job targetJob = resolveJob(mode);

            JobExecution execution = syncJobLauncher.run(targetJob, parameters);
            BatchStatus status = execution.getStatus();
            log.info("One-shot batch execution for mode: {} completed with status: {}", mode, status);

            if (status == BatchStatus.COMPLETED) {
                exitCode = EXIT_SUCCESS;
            } else {
                log.error("One-shot batch execution for mode: {} finished with non-complete status: {}", mode, status);
                exitCode = EXIT_FAILURE;
            }
        } catch (Exception e) {
            log.error("One-shot batch execution for mode: {} encountered an unhandled error: {}",
                    mode, e.getMessage(), e);
            exitCode = EXIT_FAILURE;
        } finally {
            if (Boolean.TRUE.equals(batchProperties.kubernetes().exitOnCompletion())) {
                log.info("Initiating application termination with exit code: {}", exitCode);
                SpringApplication.exit(applicationContext, () -> exitCode);
            }
        }
    }

    private void seedTemporalDimensions() {
        log.info("Ensuring baseline temporal dimensions are populated for one-shot job");
        dateDimensionService.seedDateDimension(DEFAULT_SEED_START_DATE, DEFAULT_SEED_END_DATE);
        timeDimensionService.seedTimeDimension();
    }

    JobParameters buildJobParameters(BatchExecutionMode mode, ApplicationArguments args) {
        JobParametersBuilder builder = jobParametersFactory.newBuilder()
                .addLong(BatchJobParametersFactory.TIMESTAMP_PARAM, System.currentTimeMillis());

        switch (mode) {
            case SDE_SYNC -> {
                if (args.containsOption("itemsUrl") && !args.getOptionValues("itemsUrl").isEmpty()) {
                    builder.addString("itemsUrl", args.getOptionValues("itemsUrl").get(0));
                }
                if (args.containsOption("stationsUrl") && !args.getOptionValues("stationsUrl").isEmpty()) {
                    builder.addString("stationsUrl", args.getOptionValues("stationsUrl").get(0));
                }
            }
            case DAILY_MARKET_SUMMARY -> {
                String targetDate = (args.containsOption(DailyMarketSummaryJobConfig.TARGET_DATE_PARAM)
                        && !args.getOptionValues(DailyMarketSummaryJobConfig.TARGET_DATE_PARAM).isEmpty())
                        ? args.getOptionValues(DailyMarketSummaryJobConfig.TARGET_DATE_PARAM).get(0)
                        : LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
                builder.addString(DailyMarketSummaryJobConfig.TARGET_DATE_PARAM, targetDate);
            }
            case ORDER_BOOK_LIQUIDITY -> {
                String snapshotTime = (args.containsOption(OrderBookLiquidityJobConfig.SNAPSHOT_TIME_PARAM)
                        && !args.getOptionValues(OrderBookLiquidityJobConfig.SNAPSHOT_TIME_PARAM).isEmpty())
                        ? args.getOptionValues(OrderBookLiquidityJobConfig.SNAPSHOT_TIME_PARAM).get(0)
                        : Instant.now().truncatedTo(ChronoUnit.MINUTES).toString();
                builder.addString(OrderBookLiquidityJobConfig.SNAPSHOT_TIME_PARAM, snapshotTime);
            }
            case DAEMON -> throw new IllegalStateException("Cannot build job parameters for DAEMON mode");
        }

        return builder.toJobParameters();
    }

    private Job resolveJob(BatchExecutionMode mode) {
        return switch (mode) {
            case SDE_SYNC -> sdeSyncJob;
            case DAILY_MARKET_SUMMARY -> dailyMarketSummaryJob;
            case ORDER_BOOK_LIQUIDITY -> orderBookLiquidityJob;
            case DAEMON -> throw new IllegalStateException("No one-shot job configured for DAEMON mode");
        };
    }
}
