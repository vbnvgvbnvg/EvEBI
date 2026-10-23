package com.github.serbentd.eve.batch.job.sde;

import com.github.serbentd.eve.batch.domain.DimItemEntity;
import com.github.serbentd.eve.batch.domain.DimRegionEntity;
import com.github.serbentd.eve.batch.domain.DimStationEntity;
import com.github.serbentd.eve.batch.repository.DimItemRepository;
import com.github.serbentd.eve.batch.repository.DimRegionRepository;
import com.github.serbentd.eve.batch.repository.DimStationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SdeWritersIntegrationTest {

    /** Synthetic IDs outside real EVE ranges so tests never collide with curated seed data. */
    private static final long TEST_BATTLESHIP_TYPE_ID = 999901L;
    private static final long TEST_OUTPOST_STATION_ID = 69990001L;
    private static final long TEST_CITADEL_REGION_STATION_ID = 69990002L;

    private static final long BATTLESHIP_GROUP_ID = 27L;
    private static final long SHIP_CATEGORY_ID = 6L;
    private static final long BATTLESHIPS_MARKET_GROUP_ID = 400L;

    private static final long JITA_SYSTEM_ID = 30000142L;
    private static final long THE_FORGE_REGION_ID = 10000002L;
    /** Real region absent from the curated {@code dim_regions.csv} seed. */
    private static final long THE_CITADEL_REGION_ID = 10000033L;

    private final ItemDimensionWriter itemWriter;
    private final StationDimensionWriter stationWriter;
    private final DimItemRepository itemRepository;
    private final DimStationRepository stationRepository;
    private final DimRegionRepository regionRepository;
    private final EntityManager entityManager;

    SdeWritersIntegrationTest(ItemDimensionWriter itemWriter,
                              StationDimensionWriter stationWriter,
                              DimItemRepository itemRepository,
                              DimStationRepository stationRepository,
                              DimRegionRepository regionRepository,
                              EntityManager entityManager) {
        this.itemWriter = itemWriter;
        this.stationWriter = stationWriter;
        this.itemRepository = itemRepository;
        this.stationRepository = stationRepository;
        this.regionRepository = regionRepository;
        this.entityManager = entityManager;
    }

    @Test
    @DisplayName("ItemDimensionWriter should insert and atomically upsert items on conflict, preserving createdAt")
    void itemWriter_shouldInsertAndUpsert() {
        Instant initialTime = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        itemWriter.write(new Chunk<>(List.of(
                battleship("Test Battleship", "480000.0000", initialTime))));

        Optional<DimItemEntity> persisted = itemRepository.findById(TEST_BATTLESHIP_TYPE_ID);
        assertThat(persisted).isPresent();
        assertThat(persisted.get().getTypeName()).isEqualTo("Test Battleship");

        // Execute Upsert: change name, verify updatedAt changes while createdAt remains preserved
        Instant updateTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        itemWriter.write(new Chunk<>(List.of(
                battleship("Test Battleship Mark II", "490000.0000", updateTime))));
        entityManager.clear();

        Optional<DimItemEntity> reloaded = itemRepository.findById(TEST_BATTLESHIP_TYPE_ID);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getTypeName()).isEqualTo("Test Battleship Mark II");
        assertThat(reloaded.get().getVolume()).isEqualByComparingTo("490000.0000");
        assertThat(reloaded.get().getCreatedAt()).isEqualTo(initialTime);
        assertThat(reloaded.get().getUpdatedAt()).isEqualTo(updateTime);
    }

    @Test
    @DisplayName("StationDimensionWriter should insert and atomically upsert stations on conflict, preserving createdAt")
    void stationWriter_shouldInsertAndUpsert() {
        Instant initialTime = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        stationWriter.write(new Chunk<>(List.of(
                station(TEST_OUTPOST_STATION_ID, "Test Outpost Alpha", THE_FORGE_REGION_ID, initialTime))));

        Optional<DimStationEntity> persisted = stationRepository.findById(TEST_OUTPOST_STATION_ID);
        assertThat(persisted).isPresent();
        assertThat(persisted.get().getStationName()).isEqualTo("Test Outpost Alpha");

        // Execute Upsert
        Instant updateTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        stationWriter.write(new Chunk<>(List.of(
                station(TEST_OUTPOST_STATION_ID, "Test Outpost Alpha (Upgraded)", THE_FORGE_REGION_ID, updateTime))));
        entityManager.clear();

        Optional<DimStationEntity> reloaded = stationRepository.findById(TEST_OUTPOST_STATION_ID);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStationName()).isEqualTo("Test Outpost Alpha (Upgraded)");
        assertThat(reloaded.get().getCreatedAt()).isEqualTo(initialTime);
        assertThat(reloaded.get().getUpdatedAt()).isEqualTo(updateTime);
    }

    @Test
    @DisplayName("StationDimensionWriter should stub uncatalogued regions without overwriting curated regions")
    void stationWriter_shouldRegisterMissingRegionStubs() {
        assertThat(regionRepository.findById(THE_CITADEL_REGION_ID)).isEmpty();
        String curatedForgeName = regionRepository.findById(THE_FORGE_REGION_ID)
                .map(DimRegionEntity::getRegionName)
                .orElseThrow();

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        stationWriter.write(new Chunk<>(List.of(
                station(TEST_CITADEL_REGION_STATION_ID, "Test Station in Unseeded Region", THE_CITADEL_REGION_ID, now),
                station(TEST_OUTPOST_STATION_ID, "Test Outpost Alpha", THE_FORGE_REGION_ID, now))));
        entityManager.clear();

        Optional<DimRegionEntity> stub = regionRepository.findById(THE_CITADEL_REGION_ID);
        assertThat(stub).isPresent();
        assertThat(stub.get().getRegionName()).isEqualTo("Unknown Region (" + THE_CITADEL_REGION_ID + ")");
        assertThat(stationRepository.findById(TEST_CITADEL_REGION_STATION_ID)).isPresent();

        assertThat(regionRepository.findById(THE_FORGE_REGION_ID))
                .map(DimRegionEntity::getRegionName)
                .contains(curatedForgeName);
    }

    private static DimItemEntity battleship(String typeName, String volume, Instant timestamp) {
        return DimItemEntity.builder()
                .typeId(TEST_BATTLESHIP_TYPE_ID)
                .typeName(typeName)
                .groupId(BATTLESHIP_GROUP_ID)
                .groupName("Battleship")
                .categoryId(SHIP_CATEGORY_ID)
                .categoryName("Ship")
                .marketGroupId(BATTLESHIPS_MARKET_GROUP_ID)
                .volume(new BigDecimal(volume))
                .packagedVolume(new BigDecimal("50000.0000"))
                .portionSize(1)
                .isPublished(true)
                .createdAt(timestamp)
                .updatedAt(timestamp)
                .build();
    }

    private static DimStationEntity station(long stationId, String stationName, long regionId, Instant timestamp) {
        return DimStationEntity.builder()
                .stationId(stationId)
                .stationName(stationName)
                .solarSystemId(JITA_SYSTEM_ID)
                .solarSystemName("Jita")
                .regionId(regionId)
                .securityStatus(new BigDecimal("0.95"))
                .securityClass("HIGH_SEC")
                .isStructure(false)
                .createdAt(timestamp)
                .updatedAt(timestamp)
                .build();
    }
}
