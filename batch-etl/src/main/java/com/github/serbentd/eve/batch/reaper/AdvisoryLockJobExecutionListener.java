package com.github.serbentd.eve.batch.reaper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

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

    @Override
    public void beforeJob(JobExecution jobExecution) {
        long lockId = jobExecution.getId();
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
        advisoryLockManager.unlock(lockId);
        log.info("Released session advisory lock for job execution: {}", lockId);
    }
}
