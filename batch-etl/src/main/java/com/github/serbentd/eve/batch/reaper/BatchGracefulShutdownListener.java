package com.github.serbentd.eve.batch.reaper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SmartLifecycle component that intercepts container SIGTERM / context shutdown signals.
 * Coordinates with {@link AdvisoryLockManager} to gracefully stop locally owned running
 * Spring Batch job and step executions before the database connection pool or task executor are terminated.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BatchGracefulShutdownListener implements SmartLifecycle {

    private static final int SHUTDOWN_PHASE = Integer.MAX_VALUE - 100;

    private final JobExplorer jobExplorer;
    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final AdvisoryLockManager advisoryLockManager;
    private final AdvisoryLockJobExecutionListener advisoryLockListener;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("BatchGracefulShutdownListener active (monitoring for SIGTERM graceful shutdown)");
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("BatchGracefulShutdownListener shutdown triggered. Signaling active locally-held batch executions to stop...");
            stopLocallyOwnedExecutions();
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return SHUTDOWN_PHASE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * Inspects active executions across all jobs and signals graceful termination
     * on those whose advisory locks are held by this JVM instance.
     *
     * @return count of locally owned executions signaled to stop
     */
    public int stopLocallyOwnedExecutions() {
        int stoppedCount = 0;
        for (String jobName : jobExplorer.getJobNames()) {
            Set<JobExecution> runningExecutions = jobExplorer.findRunningJobExecutions(jobName);
            for (JobExecution execution : runningExecutions) {
                long executionId = execution.getId();
                if (advisoryLockManager.isLockHeldLocally(executionId)) {
                    log.warn("Signaling graceful stop for locally owned execution id: {} (job: '{}')", executionId, jobName);
                    boolean stoppedByOperator = false;
                    try {
                        stoppedByOperator = jobOperator.stop(executionId);
                    } catch (Exception e) {
                        log.warn("Failed to stop execution id: {} via JobOperator: {}", executionId, e.getMessage());
                    }

                    JobExecution liveExecution = advisoryLockListener.getActiveExecution(executionId);
                    if (liveExecution != null && liveExecution.getStepExecutions() != null) {
                        for (StepExecution liveStep : liveExecution.getStepExecutions()) {
                            if (liveStep.getStatus().isRunning()) {
                                log.info("Setting terminate-only on active in-memory step: '{}' (stepExecutionId: {})",
                                        liveStep.getStepName(), liveStep.getId());
                                liveStep.setTerminateOnly();
                                liveStep.setStatus(BatchStatus.STOPPING);
                            }
                        }
                    } else {
                        JobExecution targetExecution = execution;
                        Collection<StepExecution> stepExecutions = targetExecution.getStepExecutions();
                        if (stepExecutions == null || stepExecutions.isEmpty()) {
                            JobExecution refreshed = jobExplorer.getJobExecution(executionId);
                            if (refreshed != null && refreshed.getStepExecutions() != null) {
                                targetExecution = refreshed;
                                stepExecutions = refreshed.getStepExecutions();
                            }
                        }

                        if (stepExecutions != null) {
                            for (StepExecution stepExecution : stepExecutions) {
                                if (stepExecution.getStatus().isRunning()) {
                                    log.info("Setting terminate-only on step: '{}' (stepExecutionId: {})",
                                            stepExecution.getStepName(), stepExecution.getId());
                                    stepExecution.setTerminateOnly();
                                    stepExecution.setStatus(BatchStatus.STOPPING);
                                    try {
                                        jobRepository.update(stepExecution);
                                    } catch (Exception e) {
                                        log.warn("Failed to update step execution id: {} in repository: {}",
                                                stepExecution.getId(), e.getMessage());
                                    }
                                }
                            }
                        }
                    }

                    if (!stoppedByOperator) {
                        execution.setStatus(BatchStatus.STOPPING);
                        try {
                            jobRepository.update(execution);
                        } catch (Exception e) {
                            log.warn("Failed to update job execution id: {} in repository: {}", executionId, e.getMessage());
                        }
                    }

                    stoppedCount++;
                } else {
                    log.debug("Execution id: {} for job '{}' is not held locally. Leaving untouched.",
                            executionId, jobName);
                }
            }
        }
        log.info("BatchGracefulShutdownListener signaled stop on {} locally-held execution(s)", stoppedCount);
        return stoppedCount;
    }
}
