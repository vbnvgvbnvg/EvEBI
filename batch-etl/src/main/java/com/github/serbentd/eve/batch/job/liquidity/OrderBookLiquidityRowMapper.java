package com.github.serbentd.eve.batch.job.liquidity;

import com.github.serbentd.eve.batch.dto.OrderBookLiquidityAggregationDto;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps SQL query result rows into OrderBookLiquidityAggregationDto records.
 */
public class OrderBookLiquidityRowMapper implements RowMapper<OrderBookLiquidityAggregationDto> {

    @Override
    public OrderBookLiquidityAggregationDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        Long locationId = rs.getLong("location_id");
        Long typeId = rs.getLong("type_id");
        Long systemId = rs.getLong("system_id");
        Long regionId = rs.getLong("region_id");

        BigDecimal bestBid = rs.getBigDecimal("best_bid");
        BigDecimal bestAsk = rs.getBigDecimal("best_ask");
        Long buyVolume = rs.getLong("buy_volume");
        Long sellVolume = rs.getLong("sell_volume");
        Integer buyOrdersCount = rs.getInt("buy_orders_count");
        Integer sellOrdersCount = rs.getInt("sell_orders_count");

        return new OrderBookLiquidityAggregationDto(
                locationId,
                typeId,
                systemId,
                regionId,
                bestBid,
                bestAsk,
                buyVolume,
                sellVolume,
                buyOrdersCount,
                sellOrdersCount
        );
    }
}
