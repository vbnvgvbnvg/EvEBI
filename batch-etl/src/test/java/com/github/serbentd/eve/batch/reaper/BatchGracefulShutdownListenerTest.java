package com.github.serbentd.eve.batch.reaper;

import com.github.serbentd.eve.batch.job.daily.DailyMarketSummaryJobConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BatchGracefulShutdownListenerTest {

    private final BatchGracefulShutdownListener shutdownListener;
    private final AdvisoryLockManager advisoryLockManager;
    private final AdvisoryLockJobExecutionListener advisoryLockListener;
    private final JobRepository jobRepository;
    private final JobExplorer jobExplorer;

    BatchGracefulShutdownListenerTest(
            BatchGracefulShutdownListener shutdownListener,
            AdvisoryLockManager advisoryLockManager,
            AdvisoryLockJobExecutionListener advisoryLockListener,
            JobRepository jobRepository,
            JobExplorer jobExplorer) {
        this.shutdownListener = shutdownListener;
        this.advisoryLockManager = advisoryLockManager;
        this.advisoryLockListener = advisoryLockListener;
        this.jobRepository = jobRepository;
        this.jobExplorer = jobExplorer;
    }

    @BeforeEach
    @AfterEach
    void cleanState() {
        advisoryLockManager.clearSimulatedLocks();
        advisoryLockListener.clearActiveExecutions();
    }

    @Test
    @DisplayName("Should verify SmartLifecycle contract and phase configuration")
    void shouldVerifySmartLifecycleContract() {
        assertThat(shutdownListener.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
        assertThat(shutdownListener.isAutoStartup()).isTrue();

        shutdownListener.start();
        assertThat(shutdownListener.isRunning()).isTrue();

        AtomicBoolean callbackFired = new AtomicBoolean(false);
        shutdownListener.stop(() -> callbackFired.set(true));

        assertThat(shutdownListener.isRunning()).isFalse();
        assertThat(callbackFired.get()).isTrue();
    }

    @Test
    @DisplayName("Should stop locally held job execution and flag running steps as terminate-only")
    void shouldStopLocallyHeldExecutionAndSetTerminateOnlyOnRunningSteps() throws Exception {
        // Arrange: Create a running execution with a running step
        JobParameters params = new JobParametersBuilder()
                .addLong("testShutdownRunId", System.currentTimeMillis())
                .toJobParameters();

        JobExecution execution = jobRepository.createJobExecution(DailyMarketSummaryJobConfig.JOB_NAME, params);
        execution.setStatus(BatchStatus.STARTED);
        jobRepository.update(execution);

        StepExecution stepExecution = execution.createStepExecution("testStep");
        stepExecution.setStatus(BatchStatus.STARTED);
        jobRepository.add(stepExecution);

        // Lock is held locally by this container instance and registered as active
        advisoryLockListener.beforeJob(execution);
        assertThat(advisoryLockManager.isLockHeldLocally(execution.getId())).isTrue();

        // Act
        int stoppedCount = shutdownListener.stopLocallyOwnedExecutions();

        // Assert
        assertThat(stoppedCount).isGreaterThanOrEqualTo(1);

        JobExecution updated = jobExplorer.getJobExecution(execution.getId());
        assertThat(updated).isNotNull();
        assertThat(updated.getStatus()).isEqualTo(BatchStatus.STOPPING);

        // The in-memory step execution has terminate-only set to interrupt active chunk loops
        assertThat(stepExecution.isTerminateOnly()).isTrue();

        // The database record reflects STOPPING status
        StepExecution updatedStep = updated.getStepExecutions().iterator().next();
        assertThat(updatedStep.getStatus()).isEqualTo(BatchStatus.STOPPING);

        // Cleanup lock and active execution
        advisoryLockListener.afterJob(execution);
    }

    @Test
    @DisplayName("Should leave remote pod job execution untouched when advisory lock is held remotely")
    void shouldLeaveRemotePodExecutionUntouched() throws Exception {
        // Arrange: Create a running execution
        JobParameters params = new JobParametersBuilder()
                .addLong("testShutdownRunId", System.currentTimeMillis() + 2000)
                .toJobParameters();

        JobExecution execution = jobRepository.createJobExecution(DailyMarketSummaryJobConfig.JOB_NAME, params);
        execution.setStatus(BatchStatus.STARTED);
        jobRepository.update(execution);

        // Lock is held externally by another pod
        advisoryLockManager.simulateLockHeld(execution.getId());
        assertThat(advisoryLockManager.isLockHeldLocally(execution.getId())).isFalse();

        // Act
        shutdownListener.stopLocallyOwnedExecutions();

        // Assert
        JobExecution untouched = jobExplorer.getJobExecution(execution.getId());
        assertThat(untouched).isNotNull();
        assertThat(untouched.getStatus()).isEqualTo(BatchStatus.STARTED);
    }
}
