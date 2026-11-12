package com.github.serbentd.eve.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * Immutable response envelope reporting the current state and step metrics of a batch job execution.
 *
 * @param jobExecutionId  unique Spring Batch execution identifier
 * @param jobName         registered job identifier
 * @param status          current Spring Batch status (e.g. {@code COMPLETED}, {@code FAILED}, {@code STARTED})
 * @param exitCode        terminal exit code
 * @param exitDescription detailed diagnostic exit description
 * @param startTime       instant the execution started
 * @param endTime         instant the execution completed, or null if currently active
 * @param stepExecutions  individual step execution summaries and throughput counters
 */
public record JobExecutionDetailDto(
        Long jobExecutionId,
        String jobName,
        String status,
        String exitCode,
        String exitDescription,
        Instant startTime,
        Instant endTime,
        List<StepExecutionDetailDto> stepExecutions
) {

    /**
     * Throughput and status metrics for an individual step execution.
     *
     * @param stepName      step identifier
     * @param status        step status
     * @param readCount     total items read
     * @param writeCount    total items successfully written
     * @param commitCount   total transactions committed
     * @param rollbackCount total transactions rolled back
     * @param exitCode      step exit code
     */
    public record StepExecutionDetailDto(
            String stepName,
            String status,
            long readCount,
            long writeCount,
            long commitCount,
            long rollbackCount,
            String exitCode
    ) {}
}
