package com.github.serbentd.eve.poller.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Immutable AMQP transit event representing an EVE Online market order snapshot.
 * Published by the ingestion poller to the market orders topic exchange.
 *
 * @param orderId      unique CCP order identifier
 * @param typeId       item type identifier (joins to invTypes)
 * @param regionId     solar region identifier (joins to mapRegions)
 * @param systemId     solar system identifier (joins to mapSolarSystems)
 * @param locationId   station ID (staStations) or Upwell Citadel structure ID
 * @param price        unit price in ISK
 * @param volumeRemain remaining units available for trade
 * @param volumeTotal  initial total units when issued
 * @param minVolume    minimum lot size required per transaction
 * @param duration     order lifetime in days (1–90)
 * @param isBuyOrder   true for buy orders (bids), false for sell orders (asks)
 * @param orderRange   visibility range (station, solarsystem, region, 1–40)
 * @param snapshotId   unique ingestion batch run identifier
 * @param issued       UTC timestamp when CCP registered the order
 * @param polledAt     UTC timestamp when this snapshot was polled from ESI
 */
public record MarketOrderEvent(
        @NotNull @Positive Long orderId,
        @NotNull @Positive Long typeId,
        @NotNull @Positive Long regionId,
        @NotNull @Positive Long systemId,
        @NotNull @Positive Long locationId,
        @NotNull @Positive BigDecimal price,
        @NotNull @PositiveOrZero Long volumeRemain,
        @NotNull @Positive Long volumeTotal,
        @NotNull @Positive Integer minVolume,
        @NotNull @Positive Integer duration,
        @NotNull Boolean isBuyOrder,
        @NotBlank String orderRange,
        @NotBlank String snapshotId,
        @NotNull OffsetDateTime issued,
        @NotNull OffsetDateTime polledAt
) {
}
