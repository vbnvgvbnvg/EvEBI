package com.github.serbentd.eve.batch.dto;

/**
 * Immutable response envelope reporting conformed calendar and time dimension seeding results.
 *
 * @param datesSeeded total calendar days seeded into {@code analytics.dim_date}
 * @param timesSeeded total minutes seeded into {@code analytics.dim_time}
 * @param message     informational summary message
 */
public record DimensionSeedResponseDto(
        long datesSeeded,
        long timesSeeded,
        String message
) {}
