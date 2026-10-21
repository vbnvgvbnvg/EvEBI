package com.github.serbentd.eve.batch.dto;

import java.math.BigDecimal;

/**
 * Immutable DTO projection representing aggregated market order metrics for a single item in a region on a given day.
 *
 * @param regionId     conformed market region ID
 * @param typeId       conformed item type ID
 * @param openPrice    first observed price on the day
 * @param highPrice    maximum observed price on the day
 * @param lowPrice     minimum observed price on the day
 * @param closePrice   last observed price on the day
 * @param volumeTraded total active/traded volume
 * @param turnoverIsk  total ISK turnover (sum of price * volume)
 * @param orderCount   total number of active or fulfilled orders
 */
public record MarketDailyAggregationDto(
        Long regionId,
        Long typeId,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal closePrice,
        Long volumeTraded,
        BigDecimal turnoverIsk,
        Integer orderCount
) {}
