package com.github.serbentd.eve.batch.service;

import com.github.serbentd.eve.batch.domain.DimStationEntity;
import com.github.serbentd.eve.batch.domain.StagingMarketOrderEntity;
import com.github.serbentd.eve.batch.repository.DimStationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves Late-Arriving Dimensions for Upwell Citadels and uncatalogued stations.
 * In Kimball dimensional modeling, operational events (market orders) can arrive
 * before external dimension master data is registered.
 * <p>
 * Uses single-pass {@code INSERT ... ON CONFLICT DO NOTHING} statements so concurrent
 * partitioned workers slicing the same region by {@code type_id} can register the same
 * newly discovered station/citadel simultaneously without primary-key race conditions
 * or transaction rollbacks.
 */
@Slf4j
@Service
public class CitadelDimensionResolver {

    private static final long UPWELL_STRUCTURE_MIN_ID = 1_000_000_000_000L;

    /**
     * Portable single-pass "insert if absent" for parent region stubs, satisfying
     * {@code fk_dim_station_region} within the same transaction. Supported by both
     * PostgreSQL and H2 in PostgreSQL compatibility mode.
     */
    private static final String REGION_STUB_SQL = """
            INSERT INTO analytics.dim_region (
                region_id, region_name, description, created_at, updated_at
            ) VALUES (
                :regionId, :regionName, :description, :createdAt, :updatedAt
            )
            ON CONFLICT DO NOTHING
            """;

    /**
     * Portable single-pass idempotent station stub registration. If a concurrent worker slice
     * or earlier SDE sync already inserted the station, the statement is a safe no-op.
     */
    private static final String STATION_STUB_SQL = """
            INSERT INTO analytics.dim_station (
                station_id, station_name, solar_system_id, solar_system_name, region_id,
                security_status, security_class, is_structure, created_at, updated_at
            ) VALUES (
                :stationId, :stationName, :solarSystemId, :solarSystemName, :regionId,
                :securityStatus, :securityClass, :isStructure, :createdAt, :updatedAt
            )
            ON CONFLICT DO NOTHING
            """;

    private final DimStationRepository dimStationRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public CitadelDimensionResolver(DimStationRepository dimStationRepository, DataSource dataSource) {
        this.dimStationRepository = dimStationRepository;
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Resolves a single station or citadel. If uncatalogued, atomically inserts an initial stub
     * via {@code ON CONFLICT DO NOTHING} so concurrent workers never collide on duplicate keys.
     *
     * @param locationId Station or Citadel ID
     * @param systemId   Solar system ID from the order context
     * @param regionId   Region ID from the order context
     * @return Existing or newly created DimStationEntity
     */
    @Transactional
    public DimStationEntity resolveOrCreate(Long locationId, Long systemId, Long regionId) {
        return dimStationRepository.findById(locationId).orElseGet(() -> {
            Instant now = Instant.now();
            DimStationEntity stub = buildStubEntity(locationId, systemId, regionId, now);

            if (regionId != null) {
                jdbcTemplate.update(REGION_STUB_SQL, buildRegionStubParams(regionId, Timestamp.from(now)));
            }
            int inserted = jdbcTemplate.update(STATION_STUB_SQL, buildStationStubParams(stub));
            if (inserted > 0) {
                log.info("Created late-arriving dimension stub for stationId: {}, systemId: {}, isStructure: {}",
                        locationId, systemId, stub.getIsStructure());
            }

            return dimStationRepository.findById(locationId).orElse(stub);
        });
    }

    /**
     * Pre-resolves all unique uncatalogued locations across a batch of staging market orders
     * before fact processing begins, avoiding individual row lookups.
     *
     * @param orders Collection of staging market orders
     */
    @Transactional
    public void resolveMissingLocations(Collection<StagingMarketOrderEntity> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }

        // 1. Group unique location IDs with an exemplary order for system and region metadata
        Map<Long, StagingMarketOrderEntity> uniqueLocations = orders.stream()
                .filter(order -> order.getLocationId() != null)
                .collect(Collectors.toMap(
                        StagingMarketOrderEntity::getLocationId,
                        order -> order,
                        (existing, replacement) -> existing
                ));

        if (uniqueLocations.isEmpty()) {
            return;
        }

        // 2. Identify which locations are already cataloged
        List<Long> existingIds = dimStationRepository.findAllById(uniqueLocations.keySet()).stream()
                .map(DimStationEntity::getStationId)
                .toList();

        Set<Long> missingIds = new HashSet<>(uniqueLocations.keySet());
        missingIds.removeAll(existingIds);

        if (missingIds.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        List<DimStationEntity> stubs = missingIds.stream()
                .map(missingId -> {
                    StagingMarketOrderEntity context = uniqueLocations.get(missingId);
                    return buildStubEntity(missingId, context.getSystemId(), context.getRegionId(), now);
                })
                .toList();

        SqlParameterSource[] regionParams = stubs.stream()
                .map(DimStationEntity::getRegionId)
                .filter(Objects::nonNull)
                .distinct()
                .map(regionId -> buildRegionStubParams(regionId, nowTs))
                .toArray(SqlParameterSource[]::new);

        if (regionParams.length > 0) {
            jdbcTemplate.batchUpdate(REGION_STUB_SQL, regionParams);
        }

        SqlParameterSource[] stationParams = stubs.stream()
                .map(this::buildStationStubParams)
                .toArray(SqlParameterSource[]::new);

        jdbcTemplate.batchUpdate(STATION_STUB_SQL, stationParams);
        log.info("Batch-registered {} late-arriving dimension stubs into dim_station", stubs.size());
    }

    /**
     * Determines whether a location ID represents a player-owned Upwell structure
     * based on EVE Online ID allocation ranges (Citadels >= 1,000,000,000,000).
     */
    public boolean isUpwellStructure(Long locationId) {
        return locationId != null && locationId >= UPWELL_STRUCTURE_MIN_ID;
    }

    private DimStationEntity buildStubEntity(Long locationId, Long systemId, Long regionId, Instant now) {
        boolean isStructure = isUpwellStructure(locationId);
        String placeholderName = isStructure
                ? "Unknown Upwell Structure (" + locationId + ")"
                : "Unknown NPC Station (" + locationId + ")";

        return DimStationEntity.builder()
                .stationId(locationId)
                .stationName(placeholderName)
                .solarSystemId(systemId)
                .solarSystemName("System " + systemId)
                .regionId(regionId)
                .securityStatus(BigDecimal.ZERO)
                .securityClass("UNKNOWN")
                .isStructure(isStructure)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private MapSqlParameterSource buildRegionStubParams(Long regionId, Timestamp now) {
        return new MapSqlParameterSource()
                .addValue("regionId", regionId)
                .addValue("regionName", "Unknown Region (" + regionId + ")")
                .addValue("description", "Late-arriving region stub registered during citadel resolution")
                .addValue("createdAt", now)
                .addValue("updatedAt", now);
    }

    private MapSqlParameterSource buildStationStubParams(DimStationEntity stub) {
        return new MapSqlParameterSource()
                .addValue("stationId", stub.getStationId())
                .addValue("stationName", stub.getStationName())
                .addValue("solarSystemId", stub.getSolarSystemId())
                .addValue("solarSystemName", stub.getSolarSystemName())
                .addValue("regionId", stub.getRegionId())
                .addValue("securityStatus", stub.getSecurityStatus())
                .addValue("securityClass", stub.getSecurityClass())
                .addValue("isStructure", stub.getIsStructure())
                .addValue("createdAt", Timestamp.from(stub.getCreatedAt()))
                .addValue("updatedAt", Timestamp.from(stub.getUpdatedAt()));
    }
}
