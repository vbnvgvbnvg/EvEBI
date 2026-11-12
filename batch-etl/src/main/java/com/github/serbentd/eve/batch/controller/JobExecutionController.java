package com.github.serbentd.eve.batch.controller;

import com.github.serbentd.eve.batch.dto.DimensionSeedResponseDto;
import com.github.serbentd.eve.batch.dto.JobExecutionDetailDto;
import com.github.serbentd.eve.batch.dto.JobLaunchResponseDto;
import com.github.serbentd.eve.batch.dto.RegionSyncResponseDto;
import com.github.serbentd.eve.batch.job.daily.DailyMarketSummaryJobConfig;
import com.github.serbentd.eve.batch.job.liquidity.OrderBookLiquidityJobConfig;
import com.github.serbentd.eve.batch.job.sde.SdeSyncJobConfig;
import com.github.serbentd.eve.batch.service.DateDimensionService;
import com.github.serbentd.eve.batch.service.EsiRegionSyncService;
import com.github.serbentd.eve.batch.service.TimeDimensionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * REST API controller exposing endpoints for triggering batch ETL jobs, synchronizing EVE SDE/ESI dimensions,
 * seeding conformed calendar/time tables, and querying job execution states via {@code curl} or Docker.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/jobs")
public class JobExecutionController {

    private final JobLauncher asyncJobLauncher;
    private final JobExplorer jobExplorer;
    private final Job sdeSyncJob;
    private final Job dailyMarketSummaryJob;
    private final Job orderBookLiquidityJob;
    private final EsiRegionSyncService esiRegionSyncService;
    private final DateDimensionService dateDimensionService;
    private final TimeDimensionService timeDimensionService;
    private final ConcurrentMap<String, Object> jobLaunchLocks = new ConcurrentHashMap<>();

    public JobExecutionController(
            @Qualifier("asyncJobLauncher") JobLauncher asyncJobLauncher,
            JobExplorer jobExplorer,
            @Qualifier(SdeSyncJobConfig.JOB_NAME) Job sdeSyncJob,
            @Qualifier(DailyMarketSummaryJobConfig.JOB_NAME) Job dailyMarketSummaryJob,
            @Qualifier(OrderBookLiquidityJobConfig.JOB_NAME) Job orderBookLiquidityJob,
            EsiRegionSyncService esiRegionSyncService,
            DateDimensionService dateDimensionService,
            TimeDimensionService timeDimensionService) {
        this.asyncJobLauncher = asyncJobLauncher;
        this.jobExplorer = jobExplorer;
        this.sdeSyncJob = sdeSyncJob;
        this.dailyMarketSummaryJob = dailyMarketSummaryJob;
        this.orderBookLiquidityJob = orderBookLiquidityJob;
        this.esiRegionSyncService = esiRegionSyncService;
        this.dateDimensionService = dateDimensionService;
        this.timeDimensionService = timeDimensionService;
    }

    /**
     * Triggers the SDE synchronization job (ESI regions -> SDE items -> SDE stations).
     */
    @PostMapping("/sde-sync")
    public ResponseEntity<JobLaunchResponseDto> triggerSdeSync(
            @RequestParam(name = "itemsUrl", required = false) String itemsUrl,
            @RequestParam(name = "stationsUrl", required = false) String stationsUrl) {

        JobParametersBuilder builder = new JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis());

        if (itemsUrl != null && !itemsUrl.isBlank()) {
            builder.addString("itemsUrl", itemsUrl);
        }
        if (stationsUrl != null && !stationsUrl.isBlank()) {
            builder.addString("stationsUrl", stationsUrl);
        }

        JobExecution execution = launchJobSafely(sdeSyncJob, builder.toJobParameters());
        log.info("Dispatched {} execution id: {}", SdeSyncJobConfig.JOB_NAME, execution.getId());

        return ResponseEntity.accepted().body(new JobLaunchResponseDto(
                execution.getId(),
                SdeSyncJobConfig.JOB_NAME,
                execution.getStatus().toString(),
                toInstant(execution.getStartTime()),
                "SDE synchronization job dispatched asynchronously"
        ));
    }

    /**
     * Programmatically syncs active EVE market regions from ESI directly into {@code analytics.dim_region}.
     */
    @PostMapping("/regions-sync")
    public ResponseEntity<RegionSyncResponseDto> triggerRegionsSync() {
        try {
            int count = esiRegionSyncService.syncRegions();
            return ResponseEntity.ok(new RegionSyncResponseDto(
                    "SUCCESS",
                    count,
                    "Successfully synchronized " + count + " regions from ESI"
            ));
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("already in progress")) {
                log.warn("ESI region sync rejected: {}", e.getMessage());
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
            }
            throw e;
        }
    }

    /**
     * Seeds conformed calendar ({@code dim_date}) and clock minutes ({@code dim_time}) dimensions.
     */
    @PostMapping("/seed-dimensions")
    public ResponseEntity<DimensionSeedResponseDto> seedDimensions(
            @RequestParam(name = "startYear", defaultValue = "2020") int startYear,
            @RequestParam(name = "endYear", defaultValue = "2035") int endYear) {

        if (startYear > endYear) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startYear cannot be greater than endYear");
        }

        long datesSeeded = dateDimensionService.seedDateDimension(
                LocalDate.of(startYear, 1, 1),
                LocalDate.of(endYear, 12, 31)
        );
        long timesSeeded = timeDimensionService.seedTimeDimension();

        return ResponseEntity.ok(new DimensionSeedResponseDto(
                datesSeeded,
                timesSeeded,
                "Conformed calendar and time dimensions seeded successfully"
        ));
    }

    /**
     * Triggers the daily market summary batch aggregation job for a specified or default target date.
     */
    @PostMapping("/daily-summary")
    public ResponseEntity<JobLaunchResponseDto> triggerDailySummary(
            @RequestParam(name = "targetDate", required = false) String targetDate) {

        String resolvedDate = targetDate;
        if (resolvedDate == null || resolvedDate.isBlank()) {
            resolvedDate = LocalDate.now(ZoneOffset.UTC).toString();
        }

        try {
            LocalDate.parse(resolvedDate);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid targetDate format (expected YYYY-MM-DD): " + resolvedDate);
        }

        JobExecution execution = launchJobSafely(dailyMarketSummaryJob, new JobParametersBuilder()
                .addString(DailyMarketSummaryJobConfig.TARGET_DATE_PARAM, resolvedDate)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters());

        log.info("Dispatched {} for targetDate: {}, execution id: {}",
                DailyMarketSummaryJobConfig.JOB_NAME, resolvedDate, execution.getId());

        return ResponseEntity.accepted().body(new JobLaunchResponseDto(
                execution.getId(),
                DailyMarketSummaryJobConfig.JOB_NAME,
                execution.getStatus().toString(),
                toInstant(execution.getStartTime()),
                "Daily market summary job dispatched asynchronously for date: " + resolvedDate
        ));
    }

    /**
     * Triggers the order book liquidity periodic snapshot batch aggregation job.
     */
    @PostMapping("/liquidity")
    public ResponseEntity<JobLaunchResponseDto> triggerLiquidity(
            @RequestParam(name = "snapshotTime", required = false) String snapshotTime) {

        String resolvedTime = snapshotTime;
        if (resolvedTime == null || resolvedTime.isBlank()) {
            resolvedTime = Instant.now().truncatedTo(ChronoUnit.MINUTES).toString();
        }

        try {
            Instant.parse(resolvedTime);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid snapshotTime format (expected ISO-8601 Instant): " + resolvedTime);
        }

        JobExecution execution = launchJobSafely(orderBookLiquidityJob, new JobParametersBuilder()
                .addString(OrderBookLiquidityJobConfig.SNAPSHOT_TIME_PARAM, resolvedTime)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters());

        log.info("Dispatched {} for snapshotTime: {}, execution id: {}",
                OrderBookLiquidityJobConfig.JOB_NAME, resolvedTime, execution.getId());

        return ResponseEntity.accepted().body(new JobLaunchResponseDto(
                execution.getId(),
                OrderBookLiquidityJobConfig.JOB_NAME,
                execution.getStatus().toString(),
                toInstant(execution.getStartTime()),
                "Order book liquidity job dispatched asynchronously for snapshot: " + resolvedTime
        ));
    }

    /**
     * Queries execution progress, status, and step metrics for a specific batch job execution.
     */
    @GetMapping("/executions/{executionId}")
    public ResponseEntity<JobExecutionDetailDto> getExecutionDetail(@PathVariable("executionId") Long executionId) {
        JobExecution execution = jobExplorer.getJobExecution(executionId);
        if (execution == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job execution not found for id: " + executionId);
        }

        List<JobExecutionDetailDto.StepExecutionDetailDto> stepDtos = execution.getStepExecutions().stream()
                .map(step -> new JobExecutionDetailDto.StepExecutionDetailDto(
                        step.getStepName(),
                        step.getStatus().toString(),
                        step.getReadCount(),
                        step.getWriteCount(),
                        step.getCommitCount(),
                        step.getRollbackCount(),
                        step.getExitStatus().getExitCode()
                ))
                .toList();

        return ResponseEntity.ok(new JobExecutionDetailDto(
                execution.getId(),
                execution.getJobInstance().getJobName(),
                execution.getStatus().toString(),
                execution.getExitStatus().getExitCode(),
                execution.getExitStatus().getExitDescription(),
                toInstant(execution.getStartTime()),
                toInstant(execution.getEndTime()),
                stepDtos
        ));
    }

    private JobExecution launchJobSafely(Job job, JobParameters jobParameters) {
        String jobName = job.getName();
        Object lock = jobLaunchLocks.computeIfAbsent(jobName, k -> new Object());
        synchronized (lock) {
            ensureJobNotRunning(jobName);
            try {
                return asyncJobLauncher.run(job, jobParameters);
            } catch (JobExecutionAlreadyRunningException e) {
                log.warn("Job '{}' is already running: {}", jobName, e.getMessage());
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Job '" + jobName + "' is currently executing", e);
            } catch (Exception e) {
                log.error("Failed to launch {}: {}", jobName, e.getMessage(), e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Failed to launch " + jobName + ": " + e.getMessage(), e);
            }
        }
    }

    private void ensureJobNotRunning(String jobName) {
        Set<JobExecution> running = jobExplorer.findRunningJobExecutions(jobName);
        if (!running.isEmpty()) {
            List<Long> runningIds = running.stream().map(JobExecution::getId).toList();
            log.warn("Job '{}' is already running with execution id(s): {}, rejecting launch request", jobName, runningIds);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Job '" + jobName + "' is currently executing (execution id: " + runningIds + ")");
        }
    }

    private static Instant toInstant(LocalDateTime localDateTime) {
        return localDateTime != null ? localDateTime.toInstant(ZoneOffset.UTC) : null;
    }
}
