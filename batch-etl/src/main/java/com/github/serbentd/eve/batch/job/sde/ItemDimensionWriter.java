package com.github.serbentd.eve.batch.job.sde;

import com.github.serbentd.eve.batch.domain.DimItemEntity;
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
 * {@code analytics.dim_item}. In production, utilizes PostgreSQL native {@code ON CONFLICT DO UPDATE}.
 * In in-memory testing environments (H2), utilizes single-statement {@code MERGE INTO}.
 */
@Component
@Slf4j
public class ItemDimensionWriter implements ItemWriter<DimItemEntity> {

    private static final String PG_UPSERT_SQL = """
            INSERT INTO analytics.dim_item (
                type_id, type_name, group_id, group_name, category_id, category_name,
                market_group_id, volume, packaged_volume, portion_size, is_published,
                created_at, updated_at
            ) VALUES (
                :typeId, :typeName, :groupId, :groupName, :categoryId, :categoryName,
                :marketGroupId, :volume, :packagedVolume, :portionSize, :isPublished,
                :createdAt, :updatedAt
            )
            ON CONFLICT (type_id) DO UPDATE SET
                type_name = EXCLUDED.type_name,
                group_id = EXCLUDED.group_id,
                group_name = EXCLUDED.group_name,
                category_id = EXCLUDED.category_id,
                category_name = EXCLUDED.category_name,
                market_group_id = EXCLUDED.market_group_id,
                volume = EXCLUDED.volume,
                packaged_volume = EXCLUDED.packaged_volume,
                portion_size = EXCLUDED.portion_size,
                is_published = EXCLUDED.is_published,
                updated_at = EXCLUDED.updated_at
            """;

    /**
     * H2 single-pass upsert. The {@code COALESCE} sub-select preserves the original {@code created_at}
     * on conflict, mirroring PostgreSQL's {@code ON CONFLICT DO UPDATE} semantics exactly.
     */
    private static final String H2_UPSERT_SQL = """
            MERGE INTO analytics.dim_item (
                type_id, type_name, group_id, group_name, category_id, category_name,
                market_group_id, volume, packaged_volume, portion_size, is_published,
                created_at, updated_at
            ) KEY(type_id) VALUES (
                :typeId, :typeName, :groupId, :groupName, :categoryId, :categoryName,
                :marketGroupId, :volume, :packagedVolume, :portionSize, :isPublished,
                COALESCE((SELECT existing.created_at FROM analytics.dim_item existing
                          WHERE existing.type_id = :typeId), :createdAt),
                :updatedAt
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final String upsertSql;

    public ItemDimensionWriter(DataSource dataSource) {
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        SqlDialect dialect = SqlDialect.detect(dataSource);
        this.upsertSql = dialect == SqlDialect.POSTGRESQL ? PG_UPSERT_SQL : H2_UPSERT_SQL;
        log.info("ItemDimensionWriter initialized with {} dialect", dialect);
    }

    @Override
    public void write(Chunk<? extends DimItemEntity> chunk) {
        if (chunk.isEmpty()) {
            return;
        }

        SqlParameterSource[] batchParams = chunk.getItems().stream()
                .map(item -> new MapSqlParameterSource()
                        .addValue("typeId", item.getTypeId())
                        .addValue("typeName", item.getTypeName())
                        .addValue("groupId", item.getGroupId())
                        .addValue("groupName", item.getGroupName())
                        .addValue("categoryId", item.getCategoryId())
                        .addValue("categoryName", item.getCategoryName())
                        .addValue("marketGroupId", item.getMarketGroupId())
                        .addValue("volume", item.getVolume())
                        .addValue("packagedVolume", item.getPackagedVolume())
                        .addValue("portionSize", item.getPortionSize())
                        .addValue("isPublished", item.getIsPublished())
                        .addValue("createdAt", Timestamp.from(item.getCreatedAt()))
                        .addValue("updatedAt", Timestamp.from(item.getUpdatedAt())))
                .toArray(SqlParameterSource[]::new);

        jdbcTemplate.batchUpdate(upsertSql, batchParams);
        log.info("Batch upserted {} item dimensions into analytics.dim_item", chunk.size());
    }
}
