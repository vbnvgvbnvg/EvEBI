package com.github.serbentd.eve.batch.job.daily;

import com.github.serbentd.eve.batch.domain.FactMarketDailySummaryEntity;
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
 * {@code analytics.fact_market_daily_summary}.
 * In production, utilizes PostgreSQL native {@code ON CONFLICT (date_id, region_id, type_id) DO UPDATE}.
 * In in-memory testing environments (H2), utilizes single-statement {@code MERGE INTO}.
 * Hierarchically guards parent dimensions (region and item) prior to fact batch execution.
 */
@Component
@Slf4j
public class DailyMarketSummaryWriter implements ItemWriter<FactMarketDailySummaryEntity> {

    private static final String REGION_STUB_SQL = """
            INSERT INTO analytics.dim_region (
                region_id, region_name, description, created_at, updated_at
            ) VALUES (
                :regionId, :regionName, :description, :createdAt, :updatedAt
            )
            ON CONFLICT DO NOTHING
            """;

    private static final String ITEM_STUB_SQL = """
            INSERT INTO analytics.dim_item (
                type_id, type_name, portion_size, is_published, created_at, updated_at
            ) VALUES (
                :typeId, :typeName, 1, TRUE, :createdAt, :updatedAt
            )
            ON CONFLICT DO NOTHING
            """;

    private static final String PG_UPSERT_SQL = """
            INSERT INTO analytics.fact_market_daily_summary (
                date_id, region_id, type_id, open_price, high_price, low_price, close_price,
                volume_traded, turnover_isk, order_count, created_at, updated_at
            ) VALUES (
                :dateId, :regionId, :typeId, :openPrice, :highPrice, :lowPrice, :closePrice,
                :volumeTraded, :turnoverIsk, :orderCount, :createdAt, :updatedAt
            )
            ON CONFLICT (date_id, region_id, type_id) DO UPDATE SET
                open_price = EXCLUDED.open_price,
                high_price = EXCLUDED.high_price,
                low_price = EXCLUDED.low_price,
                close_price = EXCLUDED.close_price,
                volume_traded = EXCLUDED.volume_traded,
                turnover_isk = EXCLUDED.turnover_isk,
                order_count = EXCLUDED.order_count,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String H2_UPSERT_SQL = """
            MERGE INTO analytics.fact_market_daily_summary (
                date_id, region_id, type_id, open_price, high_price, low_price, close_price,
                volume_traded, turnover_isk, order_count, created_at, updated_at
            ) KEY(date_id, region_id, type_id) VALUES (
                :dateId, :regionId, :typeId, :openPrice, :highPrice, :lowPrice, :closePrice,
                :volumeTraded, :turnoverIsk, :orderCount,
                COALESCE((SELECT existing.created_at FROM analytics.fact_market_daily_summary existing
                          WHERE existing.date_id = :dateId AND existing.region_id = :regionId AND existing.type_id = :typeId), :createdAt),
                :updatedAt
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final String upsertSql;

    public DailyMarketSummaryWriter(DataSource dataSource) {
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        SqlDialect dialect = SqlDialect.detect(dataSource);
        this.upsertSql = dialect == SqlDialect.POSTGRESQL ? PG_UPSERT_SQL : H2_UPSERT_SQL;
        log.info("DailyMarketSummaryWriter initialized with {} dialect", dialect);
    }

    @Override
    public void write(Chunk<? extends FactMarketDailySummaryEntity> chunk) {
        if (chunk.isEmpty()) {
            return;
        }

        registerMissingDimensions(chunk);

        SqlParameterSource[] batchParams = chunk.getItems().stream()
                .map(item -> new MapSqlParameterSource()
                        .addValue("dateId", item.getDateId())
                        .addValue("regionId", item.getRegionId())
                        .addValue("typeId", item.getTypeId())
                        .addValue("openPrice", item.getOpenPrice())
                        .addValue("highPrice", item.getHighPrice())
                        .addValue("lowPrice", item.getLowPrice())
                        .addValue("closePrice", item.getClosePrice())
                        .addValue("volumeTraded", item.getVolumeTraded())
                        .addValue("turnoverIsk", item.getTurnoverIsk())
                        .addValue("orderCount", item.getOrderCount())
                        .addValue("createdAt", Timestamp.from(item.getCreatedAt()))
                        .addValue("updatedAt", Timestamp.from(item.getUpdatedAt())))
                .toArray(SqlParameterSource[]::new);

        jdbcTemplate.batchUpdate(upsertSql, batchParams);
        log.info("Batch upserted {} daily market summaries into analytics.fact_market_daily_summary", chunk.size());
    }

    private void registerMissingDimensions(Chunk<? extends FactMarketDailySummaryEntity> chunk) {
        Timestamp now = Timestamp.from(Instant.now());

        SqlParameterSource[] regionParams = chunk.getItems().stream()
                .map(FactMarketDailySummaryEntity::getRegionId)
                .filter(Objects::nonNull)
                .distinct()
                .map(regionId -> new MapSqlParameterSource()
                        .addValue("regionId", regionId)
                        .addValue("regionName", "Unknown Region (" + regionId + ")")
                        .addValue("description", "Late-arriving region stub registered during daily summary aggregation")
                        .addValue("createdAt", now)
                        .addValue("updatedAt", now))
                .toArray(SqlParameterSource[]::new);

        if (regionParams.length > 0) {
            jdbcTemplate.batchUpdate(REGION_STUB_SQL, regionParams);
        }

        SqlParameterSource[] itemParams = chunk.getItems().stream()
                .map(FactMarketDailySummaryEntity::getTypeId)
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
