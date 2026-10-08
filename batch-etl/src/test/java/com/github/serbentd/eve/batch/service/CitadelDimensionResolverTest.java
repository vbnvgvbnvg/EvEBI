package com.github.serbentd.eve.batch.service;

import com.github.serbentd.eve.batch.domain.DimStationEntity;
import com.github.serbentd.eve.batch.domain.StagingMarketOrderEntity;
import com.github.serbentd.eve.batch.repository.DimStationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CitadelDimensionResolverTest {

    private static final Long THE_FORGE_REGION_ID = 10000002L;
    private static final Long JITA_SYSTEM_ID = 30000142L;
    private static final Long JITA_4_4_STATION_ID = 60003760L;
    private static final Long UNKNOWN_NPC_STATION_ID = 60015000L;
    private static final Long UPWELL_STRUCTURE_ID = 1040000000001L;

    private final CitadelDimensionResolver resolver;
    private final DimStationRepository dimStationRepository;

    CitadelDimensionResolverTest(CitadelDimensionResolver resolver,
                                 DimStationRepository dimStationRepository) {
        this.resolver = resolver;
        this.dimStationRepository = dimStationRepository;
    }

    @Test
    @DisplayName("Should correctly classify Upwell structure ID boundaries")
    void shouldClassifyUpwellStructureIds() {
        assertThat(resolver.isUpwellStructure(null)).isFalse();
        assertThat(resolver.isUpwellStructure(JITA_4_4_STATION_ID)).isFalse();
        assertThat(resolver.isUpwellStructure(999_999_999_999L)).isFalse();
        assertThat(resolver.isUpwellStructure(1_000_000_000_000L)).isTrue();
        assertThat(resolver.isUpwellStructure(UPWELL_STRUCTURE_ID)).isTrue();
    }

    @Test
    @DisplayName("Should create Upwell structure stub when resolving uncatalogued citadel")
    void shouldCreateUpwellStructureStub() {
        DimStationEntity citadel = resolver.resolveOrCreate(UPWELL_STRUCTURE_ID, JITA_SYSTEM_ID, THE_FORGE_REGION_ID);

        assertThat(citadel).isNotNull();
        assertThat(citadel.getStationId()).isEqualTo(UPWELL_STRUCTURE_ID);
        assertThat(citadel.getIsStructure()).isTrue();
        assertThat(citadel.getStationName()).isEqualTo("Unknown Upwell Structure (" + UPWELL_STRUCTURE_ID + ")");
        assertThat(citadel.getSolarSystemId()).isEqualTo(JITA_SYSTEM_ID);
        assertThat(citadel.getSolarSystemName()).isEqualTo("System " + JITA_SYSTEM_ID);
        assertThat(citadel.getRegionId()).isEqualTo(THE_FORGE_REGION_ID);
        assertThat(citadel.getSecurityClass()).isEqualTo("UNKNOWN");

        // Subsequent call returns existing without error
        DimStationEntity cached = resolver.resolveOrCreate(UPWELL_STRUCTURE_ID, JITA_SYSTEM_ID, THE_FORGE_REGION_ID);
        assertThat(cached.getStationId()).isEqualTo(UPWELL_STRUCTURE_ID);
    }

    @Test
    @DisplayName("Should create NPC station stub when resolving uncatalogued NPC station")
    void shouldCreateNpcStationStub() {
        DimStationEntity npc = resolver.resolveOrCreate(UNKNOWN_NPC_STATION_ID, JITA_SYSTEM_ID, THE_FORGE_REGION_ID);

        assertThat(npc).isNotNull();
        assertThat(npc.getStationId()).isEqualTo(UNKNOWN_NPC_STATION_ID);
        assertThat(npc.getIsStructure()).isFalse();
        assertThat(npc.getStationName()).isEqualTo("Unknown NPC Station (" + UNKNOWN_NPC_STATION_ID + ")");
    }

    @Test
    @DisplayName("Should return existing station when already catalogued")
    void shouldReturnExistingWhenAlreadyCatalogued() {
        // Jita 4-4 is seeded via baseline CSV
        DimStationEntity existing = resolver.resolveOrCreate(JITA_4_4_STATION_ID, JITA_SYSTEM_ID, THE_FORGE_REGION_ID);

        assertThat(existing).isNotNull();
        assertThat(existing.getStationId()).isEqualTo(JITA_4_4_STATION_ID);
        assertThat(existing.getStationName()).contains("Jita IV - Moon 4");
        assertThat(existing.getIsStructure()).isFalse();
    }

    @Test
    @DisplayName("Should handle edge cases in batch resolution (null, empty, already cataloged, null locationId)")
    void shouldHandleBatchResolutionEdgeCases() {
        // 1. Null collection
        resolver.resolveMissingLocations(null);

        // 2. Empty collection
        resolver.resolveMissingLocations(Collections.emptyList());

        // 3. Orders with null locationId
        StagingMarketOrderEntity nullLocationOrder = StagingMarketOrderEntity.builder()
                .orderId(999L)
                .locationId(null)
                .build();
        resolver.resolveMissingLocations(List.of(nullLocationOrder));

        // 4. Orders where all locations already exist in dim_station
        StagingMarketOrderEntity existingOrder = StagingMarketOrderEntity.builder()
                .orderId(1001L)
                .locationId(JITA_4_4_STATION_ID)
                .systemId(JITA_SYSTEM_ID)
                .regionId(THE_FORGE_REGION_ID)
                .build();
        resolver.resolveMissingLocations(List.of(existingOrder));

        // 5. Duplicate orders referencing the same new citadel
        Long newCitadelId = 1040000000002L;
        StagingMarketOrderEntity duplicate1 = StagingMarketOrderEntity.builder()
                .orderId(2001L)
                .locationId(newCitadelId)
                .systemId(JITA_SYSTEM_ID)
                .regionId(THE_FORGE_REGION_ID)
                .build();
        StagingMarketOrderEntity duplicate2 = StagingMarketOrderEntity.builder()
                .orderId(2002L)
                .locationId(newCitadelId)
                .systemId(JITA_SYSTEM_ID)
                .regionId(THE_FORGE_REGION_ID)
                .build();

        resolver.resolveMissingLocations(List.of(duplicate1, duplicate2));

        assertThat(dimStationRepository.existsById(newCitadelId)).isTrue();
    }
}
