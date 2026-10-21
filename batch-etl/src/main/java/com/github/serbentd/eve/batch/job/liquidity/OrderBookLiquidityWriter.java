package com.github.serbentd.eve.batch.job.liquidity;

import com.github.serbentd.eve.batch.domain.FactOrderBookLiquidityEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;

/**
 * High-throughput batch SQL writer that executes atomic deletes and inserts directly against
 * {@code analytics.fact_order_book_liquidity} using portable ANSI SQL batching.
 * Bypasses ORM persistence context overhead for maximum throughput and zero pre-read queries.
 */
@Component
@Slf4j
public class OrderBookLiquidityWriter implements ItemWriter<FactOrderBookLiquidityEntity> {

    private static final String DELETE_SQL = """
            DELETE FROM analytics.fact_order_book_liquidity
            WHERE date_id = :dateId AND time_id = :timeId AND location_id = :locationId AND type_id = :typeId
            """;

    private static final String INSERT_SQL = """
            INSERT INTO analytics.fact_order_book_liquidity (
                date_id, time_id, location_id, type_id,
                best_bid, best_ask, spread,
                buy_volume, sell_volume, buy_orders_count, sell_orders_count,
                created_at
            ) VALUES (
                :dateId, :timeId, :locationId, :typeId,
                :bestBid, :bestAsk, :spread,
                :buyVolume, :sellVolume, :buyOrdersCount, :sellOrdersCount,
                :createdAt
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public OrderBookLiquidityWriter(DataSource dataSource) {
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public void write(Chunk<? extends FactOrderBookLiquidityEntity> chunk) {
        if (chunk.isEmpty()) {
            return;
        }

        SqlParameterSource[] batchParams = chunk.getItems().stream()
                .map(item -> new MapSqlParameterSource()
                        .addValue("dateId", item.getDateId())
                        .addValue("timeId", item.getTimeId())
                        .addValue("locationId", item.getLocationId())
                        .addValue("typeId", item.getTypeId())
                        .addValue("bestBid", item.getBestBid())
                        .addValue("bestAsk", item.getBestAsk())
                        .addValue("spread", item.getSpread())
                        .addValue("buyVolume", item.getBuyVolume())
                        .addValue("sellVolume", item.getSellVolume())
                        .addValue("buyOrdersCount", item.getBuyOrdersCount())
                        .addValue("sellOrdersCount", item.getSellOrdersCount())
                        .addValue("createdAt", Timestamp.from(item.getCreatedAt())))
                .toArray(SqlParameterSource[]::new);

        jdbcTemplate.batchUpdate(DELETE_SQL, batchParams);
        jdbcTemplate.batchUpdate(INSERT_SQL, batchParams);
        log.info("Batch persisted {} order book liquidity records into analytics.fact_order_book_liquidity", chunk.size());
    }
}
