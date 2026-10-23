package com.github.serbentd.eve.batch.job.sde;

import com.github.serbentd.eve.batch.domain.DimStationEntity;
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
 * {@code analytics.dim_station}. In production, utilizes PostgreSQL native {@code ON CONFLICT DO UPDATE}.
 * In in-memory testing environments (H2), utilizes single-statement {@code MERGE INTO}.
 * Preserves referential integrity for downstream facts without requiring DELETE cascade.
 * <p>
 * Because {@code dim_station.region_id} references {@code dim_region}, and SDE station feeds span
 * every region in New Eden (not only the curated seed), each chunk first registers late-arriving
 * region stubs for any unknown region. Existing (curated or synchronized) regions are never overwritten.
 */
@Component
@Slf4j
public class StationDimensionWriter implements ItemWriter<DimStationEntity> {

    /**
     * Portable single-pass "insert if absent". {@code ON CONFLICT DO NOTHING} is native PostgreSQL
     * and is also supported by H2 in PostgreSQL compatibility mode, so no dialect switch is required.
     */
    private static final String REGION_STUB_SQL = """
            INSERT INTO analytics.dim_region (
                region_id, region_name, description, created_at, updated_at
            ) VALUES (
                :regionId, :regionName, :description, :createdAt, :updatedAt
            )
            ON CONFLICT DO NOTHING
            """;

    private static final String PG_UPSERT_SQL = """
            INSERT INTO analytics.dim_station (
                station_id, station_name, solar_system_id, solar_system_name, region_id,
                security_status, security_class, is_structure, created_at, updated_at
            ) VALUES (
                :stationId, :stationName, :solarSystemId, :solarSystemName, :regionId,
                :securityStatus, :securityClass, :isStructure, :createdAt, :updatedAt
            )
            ON CONFLICT (station_id) DO UPDATE SET
                station_name = EXCLUDED.station_name,
                solar_system_id = EXCLUDED.solar_system_id,
                solar_system_name = EXCLUDED.solar_system_name,
                region_id = EXCLUDED.region_id,
                security_status = EXCLUDED.security_status,
                security_class = EXCLUDED.security_class,
                is_structure = EXCLUDED.is_structure,
                updated_at = EXCLUDED.updated_at
            """;

    /**
     * H2 single-pass upsert. The {@code COALESCE} sub-select preserves the original {@code created_at}
     * on conflict, mirroring PostgreSQL's {@code ON CONFLICT DO UPDATE} semantics exactly.
     */
    private static final String H2_UPSERT_SQL = """
            MERGE INTO analytics.dim_station (
                station_id, station_name, solar_system_id, solar_system_name, region_id,
                security_status, security_class, is_structure, created_at, updated_at
            ) KEY(station_id) VALUES (
                :stationId, :stationName, :solarSystemId, :solarSystemName, :regionId,
                :securityStatus, :securityClass, :isStructure,
                COALESCE((SELECT existing.created_at FROM analytics.dim_station existing
                          WHERE existing.station_id = :stationId), :createdAt),
                :updatedAt
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final String upsertSql;

    public StationDimensionWriter(DataSource dataSource) {
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        SqlDialect dialect = SqlDialect.detect(dataSource);
        this.upsertSql = dialect == SqlDialect.POSTGRESQL ? PG_UPSERT_SQL : H2_UPSERT_SQL;
        log.info("StationDimensionWriter initialized with {} dialect", dialect);
    }

    @Override
    public void write(Chunk<? extends DimStationEntity> chunk) {
        if (chunk.isEmpty()) {
            return;
        }

        registerMissingRegions(chunk);

        SqlParameterSource[] batchParams = chunk.getItems().stream()
                .map(station -> new MapSqlParameterSource()
                        .addValue("stationId", station.getStationId())
                        .addValue("stationName", station.getStationName())
                        .addValue("solarSystemId", station.getSolarSystemId())
                        .addValue("solarSystemName", station.getSolarSystemName())
                        .addValue("regionId", station.getRegionId())
                        .addValue("securityStatus", station.getSecurityStatus())
                        .addValue("securityClass", station.getSecurityClass())
                        .addValue("isStructure", station.getIsStructure())
                        .addValue("createdAt", Timestamp.from(station.getCreatedAt()))
                        .addValue("updatedAt", Timestamp.from(station.getUpdatedAt())))
                .toArray(SqlParameterSource[]::new);

        jdbcTemplate.batchUpdate(upsertSql, batchParams);
        log.info("Batch upserted {} station dimensions into analytics.dim_station", chunk.size());
    }

    /**
     * Registers placeholder rows for regions referenced by this chunk that are not yet catalogued,
     * satisfying {@code fk_dim_station_region} within the same chunk transaction.
     */
    private void registerMissingRegions(Chunk<? extends DimStationEntity> chunk) {
        Timestamp now = Timestamp.from(Instant.now());
        SqlParameterSource[] regionParams = chunk.getItems().stream()
                .map(DimStationEntity::getRegionId)
                .filter(Objects::nonNull)
                .distinct()
                .map(regionId -> new MapSqlParameterSource()
                        .addValue("regionId", regionId)
                        .addValue("regionName", "Unknown Region (" + regionId + ")")
                        .addValue("description", "Late-arriving region stub registered during SDE station sync")
                        .addValue("createdAt", now)
                        .addValue("updatedAt", now))
                .toArray(SqlParameterSource[]::new);

        if (regionParams.length > 0) {
            jdbcTemplate.batchUpdate(REGION_STUB_SQL, regionParams);
        }
    }
}
