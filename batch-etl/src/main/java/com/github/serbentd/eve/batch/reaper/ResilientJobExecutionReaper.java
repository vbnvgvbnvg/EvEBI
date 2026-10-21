package com.github.serbentd.eve.batch.reaper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Startup service that identifies orphaned Spring Batch executions left in STARTING or STARTED state.
 * Uses PostgreSQL session advisory locks to prove whether the previous executing container is truly dead
 * (socket severed) versus merely slow, preventing false positives and eliminating arbitrary startup delays.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ResilientJobExecutionReaper implements ApplicationRunner {

    private final JobExplorer jobExplorer;
    private final JobRepository jobRepository;
    private final AdvisoryLockManager advisoryLockManager;

    @Override
    public void run(ApplicationArguments args) {
        reapOrphanedExecutions();
    }

    /**
     * Inspects all registered jobs for stuck executions and reaps dead ones.
     *
     * @return count of reaped executions
     */
    public int reapOrphanedExecutions() {
        int reapedCount = 0;

        for (String jobName : jobExplorer.getJobNames()) {
            Set<JobExecution> runningExecutions = jobExplorer.findRunningJobExecutions(jobName);

            for (JobExecution execution : runningExecutions) {
                long lockId = execution.getId();

                // If tryLock succeeds, NO live process is holding the connection lock -> previous pod is DEAD
                boolean canAcquireLock = advisoryLockManager.tryLock(lockId);

                if (canAcquireLock) {
                    try {
                        log.warn("Discovered orphaned execution id: {} for job: '{}' with status: {}. Socket is severed. Reaping as FAILED.",
                                execution.getId(), jobName, execution.getStatus());

                        execution.setStatus(BatchStatus.FAILED);
                        execution.setExitStatus(new ExitStatus("ABANDONED",
                                "Orphaned execution reaped on startup: container socket severed"));
                        execution.setEndTime(LocalDateTime.now());
                        jobRepository.update(execution);
                        reapedCount++;
                    } finally {
                        advisoryLockManager.unlock(lockId);
                    }
                } else {
                    log.info("Execution id: {} for job: '{}' is actively held by a live container connection. Leaving untouched.",
                            execution.getId(), jobName);
                }
            }
        }

        if (reapedCount > 0) {
            log.info("ResilientJobExecutionReaper completed. Cleaned up {} orphaned job executions.", reapedCount);
        }

        return reapedCount;
    }
}
