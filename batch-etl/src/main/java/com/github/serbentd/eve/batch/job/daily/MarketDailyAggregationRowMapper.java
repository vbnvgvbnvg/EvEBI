package com.github.serbentd.eve.batch.job.daily;

import com.github.serbentd.eve.batch.dto.MarketDailyAggregationDto;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps SQL cursor result set rows to {@link MarketDailyAggregationDto} instances.
 */
public class MarketDailyAggregationRowMapper implements RowMapper<MarketDailyAggregationDto> {

    @Override
    public MarketDailyAggregationDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new MarketDailyAggregationDto(
                rs.getLong("region_id"),
                rs.getLong("type_id"),
                rs.getBigDecimal("open_price"),
                rs.getBigDecimal("high_price"),
                rs.getBigDecimal("low_price"),
                rs.getBigDecimal("close_price"),
                rs.getLong("volume_traded"),
                rs.getBigDecimal("turnover_isk"),
                rs.getInt("order_count")
        );
    }
}
