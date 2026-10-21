package com.github.serbentd.eve.batch.dto;

import java.math.BigDecimal;

/**
 * Immutable aggregation record projected from staging.market_orders for a given location and item type.
 */
public record OrderBookLiquidityAggregationDto(
        Long locationId,
        Long typeId,
        Long systemId,
        Long regionId,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        Long buyVolume,
        Long sellVolume,
        Integer buyOrdersCount,
        Integer sellOrdersCount
) {}
