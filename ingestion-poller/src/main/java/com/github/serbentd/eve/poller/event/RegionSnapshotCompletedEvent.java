package com.github.serbentd.eve.poller.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.OffsetDateTime;

/**
 * Immutable AMQP transit event published when the ingestion poller completes fetching
 * all pages for a specific EVE Online region snapshot.
 * <p>
 * Signals the worker service to execute atomic reconciliation and mark omitted orders
 * as {@code FINISHED}.
 *
 * @param regionId    solar region identifier (joins to mapRegions)
 * @param snapshotId  unique ingestion batch run identifier
 * @param totalOrders total count of active orders fetched across all pages
 * @param totalPages  total number of HTTP pages polled from ESI
 * @param completedAt UTC timestamp when all pages were successfully polled
 */
public record RegionSnapshotCompletedEvent(
        @NotNull @Positive Long regionId,
        @NotBlank String snapshotId,
        @NotNull @PositiveOrZero Integer totalOrders,
        @NotNull @Positive Integer totalPages,
        @NotNull OffsetDateTime completedAt
) {
}
