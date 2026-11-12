package com.github.serbentd.eve.batch.job.liquidity;

import com.github.serbentd.eve.batch.domain.FactOrderBookLiquidityEntity;
import com.github.serbentd.eve.batch.job.sde.SqlDialect;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * High-throughput batch SQL writer that executes atomic upserts directly against
 * {@code analytics.fact_order_book_liquidity}.
 * In production, utilizes PostgreSQL native {@code ON CONFLICT (date_id, time_id, location_id, type_id) DO UPDATE}.
 * In in-memory testing environments (H2), utilizes single-statement {@code MERGE INTO}.
 * Hierarchically guards parent item dimensions prior to fact batch execution.
 */
@Component
@Slf4j
public class OrderBookLiquidityWriter implements ItemWriter<FactOrderBookLiquidityEntity> {

    private static final String ITEM_STUB_SQL = """
            INSERT INTO analytics.dim_item (
                type_id, type_name, portion_size, is_published, created_at, updated_at
            ) VALUES (
                :typeId, :typeName, 1, TRUE, :createdAt, :updatedAt
            )
            ON CONFLICT DO NOTHING
            """;

    private static final String PG_UPSERT_SQL = """
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
            ON CONFLICT (date_id, time_id, location_id, type_id) DO UPDATE SET
                best_bid = EXCLUDED.best_bid,
                best_ask = EXCLUDED.best_ask,
                spread = EXCLUDED.spread,
                buy_volume = EXCLUDED.buy_volume,
                sell_volume = EXCLUDED.sell_volume,
                buy_orders_count = EXCLUDED.buy_orders_count,
                sell_orders_count = EXCLUDED.sell_orders_count
            """;

    private static final String H2_UPSERT_SQL = """
            MERGE INTO analytics.fact_order_book_liquidity (
                date_id, time_id, location_id, type_id,
                best_bid, best_ask, spread,
                buy_volume, sell_volume, buy_orders_count, sell_orders_count,
                created_at
            ) KEY(date_id, time_id, location_id, type_id) VALUES (
                :dateId, :timeId, :locationId, :typeId,
                :bestBid, :bestAsk, :spread,
                :buyVolume, :sellVolume, :buyOrdersCount, :sellOrdersCount,
                COALESCE((SELECT existing.created_at FROM analytics.fact_order_book_liquidity existing
                          WHERE existing.date_id = :dateId AND existing.time_id = :timeId
                            AND existing.location_id = :locationId AND existing.type_id = :typeId), :createdAt)
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final String upsertSql;

    public OrderBookLiquidityWriter(DataSource dataSource) {
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        SqlDialect dialect = SqlDialect.detect(dataSource);
        this.upsertSql = dialect == SqlDialect.POSTGRESQL ? PG_UPSERT_SQL : H2_UPSERT_SQL;
        log.info("OrderBookLiquidityWriter initialized with {} dialect", dialect);
    }

    @Override
    public void write(Chunk<? extends FactOrderBookLiquidityEntity> chunk) {
        if (chunk.isEmpty()) {
            return;
        }

        registerMissingDimensions(chunk);

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

        jdbcTemplate.batchUpdate(upsertSql, batchParams);
        log.info("Batch upserted {} order book liquidity records into analytics.fact_order_book_liquidity", chunk.size());
    }

    private void registerMissingDimensions(Chunk<? extends FactOrderBookLiquidityEntity> chunk) {
        Timestamp now = Timestamp.from(Instant.now());

        SqlParameterSource[] itemParams = chunk.getItems().stream()
                .map(FactOrderBookLiquidityEntity::getTypeId)
                .filter(Objects::nonNull)
                .distinct()
                .map(typeId -> new MapSqlParameterSource()
                        .addValue("typeId", typeId)
                        .addValue("typeName", "Unknown Item (" + typeId + ")")
                        .addValue("createdAt", now)
                        .addValue("updatedAt", now))
                .toArray(SqlParameterSource[]::new);

        if (itemParams.length > 0) {
            jdbcTemplate.batchUpdate(ITEM_STUB_SQL, itemParams);
        }
    }
}
