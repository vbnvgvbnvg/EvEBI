package com.github.serbentd.eve.batch.service;

import com.github.serbentd.eve.batch.domain.DimStationEntity;
import com.github.serbentd.eve.batch.domain.StagingMarketOrderEntity;
import com.github.serbentd.eve.batch.repository.DimStationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves Late-Arriving Dimensions for Upwell Citadels and uncatalogued stations.
 * In Kimball dimensional modeling, operational events (market orders) can arrive
 * before external dimension master data is registered.
 * This resolver generates placeholder stub dimension records so referential integrity
 * is preserved without failing or stalling analytical batch pipelines.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CitadelDimensionResolver {

    private static final long UPWELL_STRUCTURE_MIN_ID = 1_000_000_000_000L;

    private final DimStationRepository dimStationRepository;

    /**
     * Resolves a single station or citadel. If uncatalogued, creates an initial stub.
     *
     * @param locationId Station or Citadel ID
     * @param systemId   Solar system ID from the order context
     * @param regionId   Region ID from the order context
     * @return Existing or newly created DimStationEntity
     */
    @Transactional
    public DimStationEntity resolveOrCreate(Long locationId, Long systemId, Long regionId) {
        return dimStationRepository.findById(locationId).orElseGet(() -> {
            boolean isStructure = isUpwellStructure(locationId);
            String placeholderName = isStructure
                    ? "Unknown Upwell Structure (" + locationId + ")"
                    : "Unknown NPC Station (" + locationId + ")";

            Instant now = Instant.now();
            DimStationEntity stub = DimStationEntity.builder()
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

            DimStationEntity saved = dimStationRepository.save(stub);
            log.info("Created late-arriving dimension stub for stationId: {}, systemId: {}, isStructure: {}",
                    locationId, systemId, isStructure);
            return saved;
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
        List<DimStationEntity> stubs = missingIds.stream()
                .map(missingId -> {
                    StagingMarketOrderEntity context = uniqueLocations.get(missingId);
                    boolean isStructure = isUpwellStructure(missingId);
                    String placeholderName = isStructure
                            ? "Unknown Upwell Structure (" + missingId + ")"
                            : "Unknown NPC Station (" + missingId + ")";

                    return DimStationEntity.builder()
                            .stationId(missingId)
                            .stationName(placeholderName)
                            .solarSystemId(context.getSystemId())
                            .solarSystemName("System " + context.getSystemId())
                            .regionId(context.getRegionId())
                            .securityStatus(BigDecimal.ZERO)
                            .securityClass("UNKNOWN")
                            .isStructure(isStructure)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
                })
                .toList();

        dimStationRepository.saveAll(stubs);
        log.info("Batch-registered {} late-arriving dimension stubs into dim_station", stubs.size());
    }

    /**
     * Determines whether a location ID represents a player-owned Upwell structure
     * based on EVE Online ID allocation ranges (Citadels >= 1,000,000,000,000).
     */
    public boolean isUpwellStructure(Long locationId) {
        return locationId != null && locationId >= UPWELL_STRUCTURE_MIN_ID;
    }
}
