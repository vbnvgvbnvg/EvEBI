package com.github.serbentd.eve.batch.job.daily;

import com.github.serbentd.eve.batch.domain.FactMarketDailySummaryEntity;
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
 * High-throughput batch SQL writer that executes atomic upserts directly against
 * {@code analytics.fact_market_daily_summary} using portable ANSI SQL delete-and-insert batching.
 * Bypasses ORM persistence context overhead for maximum throughput and zero pre-read queries.
 */
@Component
@Slf4j
public class DailyMarketSummaryWriter implements ItemWriter<FactMarketDailySummaryEntity> {

    private static final String DELETE_SQL = """
            DELETE FROM analytics.fact_market_daily_summary
            WHERE date_id = :dateId AND region_id = :regionId AND type_id = :typeId
            """;

    private static final String INSERT_SQL = """
            INSERT INTO analytics.fact_market_daily_summary (
                date_id, region_id, type_id, open_price, high_price, low_price, close_price,
                volume_traded, turnover_isk, order_count, created_at, updated_at
            ) VALUES (
                :dateId, :regionId, :typeId, :openPrice, :highPrice, :lowPrice, :closePrice,
                :volumeTraded, :turnoverIsk, :orderCount, :createdAt, :updatedAt
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DailyMarketSummaryWriter(DataSource dataSource) {
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public void write(Chunk<? extends FactMarketDailySummaryEntity> chunk) {
        if (chunk.isEmpty()) {
            return;
        }

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

        jdbcTemplate.batchUpdate(DELETE_SQL, batchParams);
        jdbcTemplate.batchUpdate(INSERT_SQL, batchParams);
        log.info("Batch upserted {} daily market summaries into analytics.fact_market_daily_summary", chunk.size());
    }
}
