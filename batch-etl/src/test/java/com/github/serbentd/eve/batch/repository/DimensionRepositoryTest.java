package com.github.serbentd.eve.batch.repository;

import com.github.serbentd.eve.batch.domain.DimItemEntity;
import com.github.serbentd.eve.batch.domain.DimRegionEntity;
import com.github.serbentd.eve.batch.domain.DimStationEntity;
import com.github.serbentd.eve.batch.domain.QDimItemEntity;
import com.github.serbentd.eve.batch.domain.QDimStationEntity;
import com.github.serbentd.eve.batch.domain.StagingMarketOrderEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DimensionRepositoryTest {

    private static final Long THE_FORGE_REGION_ID = 10000002L;
    private static final Long DOMAIN_REGION_ID = 10000043L;
    private static final Long JITA_SYSTEM_ID = 30000142L;
    private static final Long JITA_4_4_STATION_ID = 60003760L;
    private static final Long CITADEL_STRUCTURE_ID = 1028858195912L;
    private static final Long TRITANIUM_TYPE_ID = 34L;
    private static final Long MINERAL_GROUP_ID = 18L;
    private static final Long MATERIAL_CATEGORY_ID = 4L;

    private final DimRegionRepository dimRegionRepository;
    private final DimItemRepository dimItemRepository;
    private final DimStationRepository dimStationRepository;
    private final StagingMarketOrderRepository stagingMarketOrderRepository;

    DimensionRepositoryTest(DimRegionRepository dimRegionRepository,
                            DimItemRepository dimItemRepository,
                            DimStationRepository dimStationRepository,
                            StagingMarketOrderRepository stagingMarketOrderRepository) {
        this.dimRegionRepository = dimRegionRepository;
        this.dimItemRepository = dimItemRepository;
        this.dimStationRepository = dimStationRepository;
        this.stagingMarketOrderRepository = stagingMarketOrderRepository;
    }

    @Test
    @DisplayName("Should verify that Liquibase baseline CSV seed populated core reference dimensions")
    void shouldVerifyBaselineSeedData() {
        // 1. Verify seeded trade regions
        assertThat(dimRegionRepository.count()).isGreaterThanOrEqualTo(6);
        Optional<DimRegionEntity> forge = dimRegionRepository.findByRegionName("The Forge");
        assertThat(forge).isPresent();
        assertThat(forge.get().getRegionId()).isEqualTo(THE_FORGE_REGION_ID);

        Optional<DimRegionEntity> domain = dimRegionRepository.findByRegionName("Domain");
        assertThat(domain).isPresent();
        assertThat(domain.get().getRegionId()).isEqualTo(DOMAIN_REGION_ID);

        // 2. Verify seeded NPC trade stations
        Optional<DimStationEntity> jita = dimStationRepository.findById(JITA_4_4_STATION_ID);
        assertThat(jita).isPresent();
        assertThat(jita.get().getStationName()).contains("Jita IV - Moon 4");
        assertThat(jita.get().getSolarSystemName()).isEqualTo("Jita");
        assertThat(jita.get().getRegionId()).isEqualTo(THE_FORGE_REGION_ID);
        assertThat(jita.get().getIsStructure()).isFalse();

        // 3. Verify seeded items (minerals, PLEX, ships)
        Optional<DimItemEntity> tritanium = dimItemRepository.findById(TRITANIUM_TYPE_ID);
        assertThat(tritanium).isPresent();
        assertThat(tritanium.get().getTypeName()).isEqualTo("Tritanium");
        assertThat(tritanium.get().getGroupName()).isEqualTo("Mineral");

        Optional<DimItemEntity> plex = dimItemRepository.findById(29668L);
        assertThat(plex).isPresent();
        assertThat(plex.get().getTypeName()).isEqualTo("PLEX");

        Optional<DimItemEntity> caracal = dimItemRepository.findById(621L);
        assertThat(caracal).isPresent();
        assertThat(caracal.get().getTypeName()).isEqualTo("Caracal");
        assertThat(caracal.get().getCategoryName()).isEqualTo("Ship");
    }

    @Test
    @DisplayName("Should persist and retrieve conformed trade regions")
    void shouldPersistAndFindRegion() {
        Instant now = Instant.now();
        Long customRegionId = 10000068L; // Verge Vendor
        DimRegionEntity vergeVendor = DimRegionEntity.builder()
                .regionId(customRegionId)
                .regionName("Verge Vendor")
                .description("Gallente Federation frontier region")
                .createdAt(now)
                .updatedAt(now)
                .build();

        dimRegionRepository.save(vergeVendor);

        Optional<DimRegionEntity> retrieved = dimRegionRepository.findById(customRegionId);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getRegionName()).isEqualTo("Verge Vendor");

        Optional<DimRegionEntity> byName = dimRegionRepository.findByRegionName("Verge Vendor");
        assertThat(byName).isPresent();
    }

    @Test
    @DisplayName("Should query item dimension using QueryDSL predicates across seeded and added items")
    void shouldPersistAndQueryItemsWithQueryDsl() {
        QDimItemEntity qItem = QDimItemEntity.dimItemEntity;

        // Verify seeded minerals in group 18 (Tritanium, Pyerite, Mexallon, Isogen, Nocxium, Zydrine, Megacyte, Morphite)
        Iterable<DimItemEntity> minerals = dimItemRepository.findAll(qItem.groupId.eq(MINERAL_GROUP_ID));
        assertThat(minerals).hasSize(8);

        // Verify specific item lookup via QueryDSL
        DimItemEntity tritanium = dimItemRepository.findOne(qItem.typeId.eq(TRITANIUM_TYPE_ID)).orElse(null);
        assertThat(tritanium).isNotNull();
        assertThat(tritanium.getTypeName()).isEqualTo("Tritanium");
        assertThat(tritanium.getGroupName()).isEqualTo("Mineral");
    }

    @Test
    @DisplayName("Should persist Upwell Citadel and query structures alongside seeded NPC stations")
    void shouldPersistStationsAndCitadels() {
        Instant now = Instant.now();

        // Seeded stations include 5 high-sec NPC stations (Jita, Amarr, Dodixie, Rens, Hek)
        // Now persist an Upwell Citadel: Perimeter Trading Tower
        DimStationEntity citadel = DimStationEntity.builder()
                .stationId(CITADEL_STRUCTURE_ID)
                .stationName("Perimeter - Tranquility Trading Tower")
                .solarSystemId(30000144L)
                .solarSystemName("Perimeter")
                .regionId(THE_FORGE_REGION_ID)
                .securityStatus(new BigDecimal("1.00"))
                .securityClass("HIGH_SEC")
                .isStructure(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        dimStationRepository.save(citadel);

        QDimStationEntity qStation = QDimStationEntity.dimStationEntity;
        Iterable<DimStationEntity> structures = dimStationRepository.findAll(qStation.isStructure.isTrue());
        assertThat(structures).hasSize(1).first().extracting(DimStationEntity::getStationId).isEqualTo(CITADEL_STRUCTURE_ID);

        // 5 seeded high-sec NPC stations + 1 high-sec Citadel = 6
        Iterable<DimStationEntity> highSecStations = dimStationRepository.findAll(qStation.securityClass.eq("HIGH_SEC"));
        assertThat(highSecStations).hasSize(6);
    }

    @Test
    @DisplayName("Should persist and query operational staging orders")
    void shouldPersistStagingOrders() {
        Instant now = Instant.now();
        StagingMarketOrderEntity order = StagingMarketOrderEntity.builder()
                .orderId(123456789L)
                .typeId(TRITANIUM_TYPE_ID)
                .regionId(THE_FORGE_REGION_ID)
                .systemId(JITA_SYSTEM_ID)
                .locationId(JITA_4_4_STATION_ID)
                .price(new BigDecimal("4.25"))
                .volumeRemain(500000L)
                .volumeTotal(1000000L)
                .minVolume(1)
                .duration(90)
                .isBuyOrder(false)
                .orderRange("region")
                .status("ACTIVE")
                .isActive(true)
                .snapshotId("snap-001")
                .issued(now.minusSeconds(3600))
                .createdAt(now)
                .updatedAt(now)
                .build();

        stagingMarketOrderRepository.save(order);

        Optional<StagingMarketOrderEntity> retrieved = stagingMarketOrderRepository.findById(123456789L);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getPrice()).isEqualByComparingTo("4.25");
        assertThat(retrieved.get().getIsBuyOrder()).isFalse();
    }
}
