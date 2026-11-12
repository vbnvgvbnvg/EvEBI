package com.github.serbentd.eve.batch.reaper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JobExecutionListener that holds an advisory lock for the lifetime of a job execution.
 * If the container is killed or fails abruptly, the database connection drops and PostgreSQL
 * automatically releases the lock.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AdvisoryLockJobExecutionListener implements JobExecutionListener {

    private final AdvisoryLockManager advisoryLockManager;
    private final Map<Long, JobExecution> activeExecutions = new ConcurrentHashMap<>();

    @Override
    public void beforeJob(JobExecution jobExecution) {
        long lockId = jobExecution.getId();
        activeExecutions.put(lockId, jobExecution);
        boolean locked = advisoryLockManager.tryLock(lockId);
        if (locked) {
            log.info("Acquired session advisory lock for job execution: {}", lockId);
        } else {
            log.warn("Could not acquire advisory lock for job execution: {}", lockId);
        }
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        long lockId = jobExecution.getId();
        activeExecutions.remove(lockId);
        advisoryLockManager.unlock(lockId);
        log.info("Released session advisory lock for job execution: {}", lockId);
    }

    /**
     * Retrieves the live in-memory JobExecution running on this instance, if active.
     */
    public JobExecution getActiveExecution(long executionId) {
        return activeExecutions.get(executionId);
    }

    /**
     * Retrieves all live in-memory JobExecutions currently active on this instance.
     */
    public Collection<JobExecution> getActiveExecutions() {
        return Collections.unmodifiableCollection(activeExecutions.values());
    }

    /**
     * Clears active in-memory executions (primarily for testing purposes).
     */
    public void clearActiveExecutions() {
        activeExecutions.clear();
    }
}
