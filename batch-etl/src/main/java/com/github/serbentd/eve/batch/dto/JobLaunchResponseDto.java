package com.github.serbentd.eve.batch.dto;

import java.time.Instant;

/**
 * Immutable response envelope returned upon launching an asynchronous batch job execution.
 *
 * @param jobExecutionId unique Spring Batch execution identifier
 * @param jobName        registered job identifier
 * @param status         initial batch status (typically {@code STARTING} or {@code STARTED})
 * @param startTime      instant the execution was dispatched
 * @param message        informational execution status message
 */
public record JobLaunchResponseDto(
        Long jobExecutionId,
        String jobName,
        String status,
        Instant startTime,
        String message
) {}
