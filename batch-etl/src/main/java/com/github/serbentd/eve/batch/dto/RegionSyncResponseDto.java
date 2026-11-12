package com.github.serbentd.eve.batch.dto;

/**
 * Immutable response envelope reporting the outcome of an on-demand ESI market region synchronization.
 *
 * @param status              synchronization outcome status (e.g. {@code SUCCESS})
 * @param regionsSynchronized total market regions synchronized and upserted into {@code analytics.dim_region}
 * @param message             informational summary message
 */
public record RegionSyncResponseDto(
        String status,
        int regionsSynchronized,
        String message
) {}
