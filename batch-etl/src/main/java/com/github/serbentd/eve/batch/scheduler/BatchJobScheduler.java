package com.github.serbentd.eve.batch.scheduler;

import com.github.serbentd.eve.batch.config.BatchJobParametersFactory;
import com.github.serbentd.eve.batch.config.BatchProperties;
import com.github.serbentd.eve.batch.config.PartitionRole;
import com.github.serbentd.eve.batch.job.daily.DailyMarketSummaryJobConfig;
import com.github.serbentd.eve.batch.job.liquidity.OrderBookLiquidityJobConfig;
import com.github.serbentd.eve.batch.job.sde.SdeSyncJobConfig;
import com.github.serbentd.eve.batch.service.DateDimensionService;
import com.github.serbentd.eve.batch.service.TimeDimensionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Scheduled cron coordinator for recurring Spring Batch ETL tasks (SDE sync, daily aggregations,
 * periodic liquidity snapshots) and initial dimension seeding.
 * Role-gated to execute only on {@code STANDALONE} or {@code MANAGER} nodes.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "eve.batch.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BatchJobScheduler {

    private static final LocalDate DEFAULT_SEED_START_DATE = LocalDate.of(2020, 1, 1);
    private static final LocalDate DEFAULT_SEED_END_DATE = LocalDate.of(2035, 12, 31);

    private final JobLauncher asyncJobLauncher;
    private final Job sdeSyncJob;
    private final Job dailyMarketSummaryJob;
    private final Job orderBookLiquidityJob;
    private final BatchProperties batchProperties;
    private final DateDimensionService dateDimensionService;
    private final TimeDimensionService timeDimensionService;
    private final BatchJobParametersFactory jobParametersFactory;

    public BatchJobScheduler(
            @Qualifier("asyncJobLauncher") JobLauncher asyncJobLauncher,
            @Qualifier(SdeSyncJobConfig.JOB_NAME) Job sdeSyncJob,
            @Qualifier(DailyMarketSummaryJobConfig.JOB_NAME) Job dailyMarketSummaryJob,
            @Qualifier(OrderBookLiquidityJobConfig.JOB_NAME) Job orderBookLiquidityJob,
            BatchProperties batchProperties,
            DateDimensionService dateDimensionService,
            TimeDimensionService timeDimensionService,
            BatchJobParametersFactory jobParametersFactory) {
        this.asyncJobLauncher = asyncJobLauncher;
        this.sdeSyncJob = sdeSyncJob;
        this.dailyMarketSummaryJob = dailyMarketSummaryJob;
        this.orderBookLiquidityJob = orderBookLiquidityJob;
        this.batchProperties = batchProperties;
        this.dateDimensionService = dateDimensionService;
        this.timeDimensionService = timeDimensionService;
        this.jobParametersFactory = jobParametersFactory;
    }

    /**
     * Seeds baseline calendar (2020-2035) and clock (1,440 minutes) dimensions if unpopulated on startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedDimensionsOnStartup() {
        if (isWorkerNode()) {
            return;
        }

        log.info("Verifying conformed temporal dimensions on startup");
        dateDimensionService.seedDateDimension(DEFAULT_SEED_START_DATE, DEFAULT_SEED_END_DATE);
        timeDimensionService.seedTimeDimension();
    }

    /**
     * Scheduled trigger for SDE synchronization (ESI regions, SDE items, SDE stations).
     */
    @Scheduled(cron = "${eve.batch.schedule.sde-sync-cron}")
    public void scheduleSdeSync() {
        if (isWorkerNode()) {
            return;
        }

        log.info("Executing scheduled SDE synchronization trigger");
        try {
            asyncJobLauncher.run(sdeSyncJob, jobParametersFactory.newBuilder()
                    .addLong(BatchJobParametersFactory.TIMESTAMP_PARAM, System.currentTimeMillis())
                    .toJobParameters());
        } catch (Exception e) {
            log.error("Scheduled SDE synchronization job launch failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Scheduled trigger for daily market summary rollup (aggregates previous UTC calendar day).
     */
    @Scheduled(cron = "${eve.batch.schedule.daily-summary-cron}")
    public void scheduleDailySummary() {
        if (isWorkerNode()) {
            return;
        }

        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        log.info("Executing scheduled daily market summary trigger for date: {}", yesterday);
        try {
            asyncJobLauncher.run(dailyMarketSummaryJob, jobParametersFactory.newBuilder()
                    .addString(DailyMarketSummaryJobConfig.TARGET_DATE_PARAM, yesterday.toString())
                    .addLong(BatchJobParametersFactory.TIMESTAMP_PARAM, System.currentTimeMillis())
                    .toJobParameters());
        } catch (Exception e) {
            log.error("Scheduled daily market summary job launch failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Scheduled trigger for order book liquidity periodic snapshot (every 15 minutes).
     */
    @Scheduled(cron = "${eve.batch.schedule.liquidity-cron}")
    public void scheduleLiquiditySnapshot() {
        if (isWorkerNode()) {
            return;
        }

        Instant snapshotTime = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        log.info("Executing scheduled order book liquidity trigger for timestamp: {}", snapshotTime);
        try {
            asyncJobLauncher.run(orderBookLiquidityJob, jobParametersFactory.newBuilder()
                    .addString(OrderBookLiquidityJobConfig.SNAPSHOT_TIME_PARAM, snapshotTime.toString())
                    .addLong(BatchJobParametersFactory.TIMESTAMP_PARAM, System.currentTimeMillis())
                    .toJobParameters());
        } catch (Exception e) {
            log.error("Scheduled order book liquidity job launch failed: {}", e.getMessage(), e);
        }
    }

    private boolean isWorkerNode() {
        return batchProperties.remotePartitioning().role() == PartitionRole.WORKER;
    }
}
