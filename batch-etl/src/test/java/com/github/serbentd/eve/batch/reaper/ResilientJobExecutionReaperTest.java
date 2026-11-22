package com.github.serbentd.eve.batch.reaper;

import com.github.serbentd.eve.batch.job.daily.DailyMarketSummaryJobConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ResilientJobExecutionReaperTest {

    private final ResilientJobExecutionReaper reaper;
    private final AdvisoryLockManager advisoryLockManager;
    private final JobRepository jobRepository;

    ResilientJobExecutionReaperTest(
            ResilientJobExecutionReaper reaper,
            AdvisoryLockManager advisoryLockManager,
            JobRepository jobRepository) {
        this.reaper = reaper;
        this.advisoryLockManager = advisoryLockManager;
        this.jobRepository = jobRepository;
    }

    @BeforeEach
    @AfterEach
    void cleanState() {
        advisoryLockManager.clearSimulatedLocks();
    }

    @Test
    @DisplayName("Should detect orphaned job execution with severed socket and mark as FAILED/ABANDONED")
    void shouldReapOrphanedJobWhenSocketIsSevered() throws Exception {
        // Arrange: Create a stuck execution in STARTED state
        JobParameters params = new JobParametersBuilder()
                .addLong("testRunId", System.currentTimeMillis())
                .toJobParameters();

        JobExecution execution = jobRepository.createJobExecution(DailyMarketSummaryJobConfig.JOB_NAME, params);
        execution.setStatus(BatchStatus.STARTED);
        jobRepository.update(execution);

        // Lock is not held (simulates dead container with severed socket)
        // Act
        int reapedCount = reaper.reapOrphanedExecutions();

        // Assert
        assertThat(reapedCount).isGreaterThanOrEqualTo(1);

        JobExecution updated = jobRepository.getJobExecution(execution.getId());
        assertThat(updated).isNotNull();
        assertThat(updated.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(updated.getExitStatus().getExitCode()).isEqualTo("ABANDONED");
        assertThat(updated.getEndTime()).isNotNull();
    }

    @Test
    @DisplayName("Should preserve active job execution when advisory lock is held by a live container connection")
    void shouldNotReapActiveJobWhenLockIsHeldByLiveConnection() throws Exception {
        // Arrange: Create a running execution
        JobParameters params = new JobParametersBuilder()
                .addLong("testRunId", System.currentTimeMillis() + 1000)
                .toJobParameters();

        JobExecution execution = jobRepository.createJobExecution(DailyMarketSummaryJobConfig.JOB_NAME, params);
        execution.setStatus(BatchStatus.STARTED);
        jobRepository.update(execution);

        // Simulate active TCP socket connection holding the advisory lock
        advisoryLockManager.simulateLockHeld(execution.getId());

        // Act
        int reapedCount = reaper.reapOrphanedExecutions();

        // Assert: Active execution was not reaped
        JobExecution active = jobRepository.getJobExecution(execution.getId());
        assertThat(active).isNotNull();
        assertThat(active.getStatus()).isEqualTo(BatchStatus.STARTED);
    }
}
