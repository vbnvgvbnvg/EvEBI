package com.github.serbentd.eve.batch.repository;

import com.github.serbentd.eve.batch.domain.DimDateEntity;
import com.github.serbentd.eve.batch.domain.DimStationEntity;
import com.github.serbentd.eve.batch.domain.FactMarketDailySummaryEntity;
import com.github.serbentd.eve.batch.domain.FactOrderBookLiquidityEntity;
import com.github.serbentd.eve.batch.domain.QFactMarketDailySummaryEntity;
import com.github.serbentd.eve.batch.domain.QFactOrderBookLiquidityEntity;
import com.github.serbentd.eve.batch.domain.StagingMarketOrderEntity;
import com.github.serbentd.eve.batch.service.CitadelDimensionResolver;
import com.github.serbentd.eve.batch.service.DateDimensionService;
import com.github.serbentd.eve.batch.service.TimeDimensionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class FactRepositoryTest {

    private static final Long THE_FORGE_REGION_ID = 10000002L;
    private static final Long JITA_SYSTEM_ID = 30000142L;
    private static final Long JITA_4_4_STATION_ID = 60003760L;
    private static final Long TRITANIUM_TYPE_ID = 34L;
    private static final Long UPWELL_CITADEL_ID = 1030000000001L;

    private final FactMarketDailySummaryRepository factDailyRepository;
    private final FactOrderBookLiquidityRepository factLiquidityRepository;
    private final DimStationRepository dimStationRepository;
    private final DateDimensionService dateDimensionService;
    private final TimeDimensionService timeDimensionService;
    private final CitadelDimensionResolver citadelDimensionResolver;

    FactRepositoryTest(FactMarketDailySummaryRepository factDailyRepository,
                       FactOrderBookLiquidityRepository factLiquidityRepository,
                       DimStationRepository dimStationRepository,
                       DateDimensionService dateDimensionService,
                       TimeDimensionService timeDimensionService,
                       CitadelDimensionResolver citadelDimensionResolver) {
        this.factDailyRepository = factDailyRepository;
        this.factLiquidityRepository = factLiquidityRepository;
        this.dimStationRepository = dimStationRepository;
        this.dateDimensionService = dateDimensionService;
        this.timeDimensionService = timeDimensionService;
        this.citadelDimensionResolver = citadelDimensionResolver;
    }

    @BeforeEach
    void setUp() {
        // Ensure calendar horizons and clock minutes exist for foreign key integrity
        dateDimensionService.seedDateDimension(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5));
        timeDimensionService.seedTimeDimension();
    }

    @Test
    @DisplayName("Should persist and query daily market summary periodic snapshot fact record")
    void shouldPersistAndQueryDailyMarketSummary() {
        Instant now = Instant.now();
        FactMarketDailySummaryEntity summary = FactMarketDailySummaryEntity.builder()
                .dateId(20260101)
                .regionId(THE_FORGE_REGION_ID)
                .typeId(TRITANIUM_TYPE_ID)
                .openPrice(new BigDecimal("4.1000"))
                .highPrice(new BigDecimal("4.5000"))
                .lowPrice(new BigDecimal("4.0500"))
                .closePrice(new BigDecimal("4.3500"))
                .volumeTraded(50_000_000L)
                .turnoverIsk(new BigDecimal("215000000.0000"))
                .orderCount(1450)
                .createdAt(now)
                .updatedAt(now)
                .build();

        FactMarketDailySummaryEntity saved = factDailyRepository.save(summary);
        assertThat(saved.getSummaryFactId()).isNotNull();

        Optional<FactMarketDailySummaryEntity> retrieved = factDailyRepository
                .findByDateIdAndRegionIdAndTypeId(20260101, THE_FORGE_REGION_ID, TRITANIUM_TYPE_ID);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getVolumeTraded()).isEqualTo(50_000_000L);
        assertThat(retrieved.get().getTurnoverIsk()).isEqualByComparingTo("215000000.0000");

        // QueryDSL analytical predicate
        QFactMarketDailySummaryEntity qFact = QFactMarketDailySummaryEntity.factMarketDailySummaryEntity;
        Iterable<FactMarketDailySummaryEntity> results = factDailyRepository.findAll(
                qFact.typeId.eq(TRITANIUM_TYPE_ID).and(qFact.volumeTraded.gt(10_000_000L))
        );
        assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("Should persist and query order book liquidity periodic fact record")
    void shouldPersistAndQueryOrderBookLiquidity() {
        Instant now = Instant.now();
        FactOrderBookLiquidityEntity liquidity = FactOrderBookLiquidityEntity.builder()
                .dateId(20260101)
                .timeId(1100) // 11:00 UTC
                .locationId(JITA_4_4_STATION_ID)
                .typeId(TRITANIUM_TYPE_ID)
                .bestBid(new BigDecimal("4.2000"))
                .bestAsk(new BigDecimal("4.2500"))
                .spread(new BigDecimal("0.0500"))
                .buyVolume(120_000_000L)
                .sellVolume(95_000_000L)
                .buyOrdersCount(85)
                .sellOrdersCount(112)
                .createdAt(now)
                .build();

        FactOrderBookLiquidityEntity saved = factLiquidityRepository.save(liquidity);
        assertThat(saved.getLiquidityFactId()).isNotNull();

        Optional<FactOrderBookLiquidityEntity> retrieved = factLiquidityRepository
                .findByDateIdAndTimeIdAndLocationIdAndTypeId(20260101, 1100, JITA_4_4_STATION_ID, TRITANIUM_TYPE_ID);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getSpread()).isEqualByComparingTo("0.0500");
        assertThat(retrieved.get().getBuyVolume()).isEqualTo(120_000_000L);

        // QueryDSL predicate
        QFactOrderBookLiquidityEntity qLiquidity = QFactOrderBookLiquidityEntity.factOrderBookLiquidityEntity;
        Iterable<FactOrderBookLiquidityEntity> tightSpreadOrders = factLiquidityRepository.findAll(
                qLiquidity.spread.lt(new BigDecimal("0.1000"))
        );
        assertThat(tightSpreadOrders).hasSize(1);
    }

    @Test
    @DisplayName("Should resolve late-arriving Upwell Citadel and allow foreign-key fact persistence")
    void shouldResolveLateArrivingCitadelAndPersistSnapshot() {
        // 1. Resolve uncatalogued Upwell Citadel
        DimStationEntity citadel = citadelDimensionResolver.resolveOrCreate(
                UPWELL_CITADEL_ID, JITA_SYSTEM_ID, THE_FORGE_REGION_ID
        );
        assertThat(citadel.getStationId()).isEqualTo(UPWELL_CITADEL_ID);
        assertThat(citadel.getIsStructure()).isTrue();
        assertThat(citadel.getStationName()).contains("Unknown Upwell Structure");

        // 2. Persist fact record referencing the newly created citadel stub
        Instant now = Instant.now();
        FactOrderBookLiquidityEntity liquidity = FactOrderBookLiquidityEntity.builder()
                .dateId(20260101)
                .timeId(1200) // 12:00 UTC
                .locationId(UPWELL_CITADEL_ID)
                .typeId(TRITANIUM_TYPE_ID)
                .bestBid(new BigDecimal("4.1800"))
                .bestAsk(new BigDecimal("4.2400"))
                .spread(new BigDecimal("0.0600"))
                .buyVolume(50_000_000L)
                .sellVolume(40_000_000L)
                .buyOrdersCount(30)
                .sellOrdersCount(25)
                .createdAt(now)
                .build();

        FactOrderBookLiquidityEntity saved = factLiquidityRepository.save(liquidity);
        assertThat(saved.getLiquidityFactId()).isNotNull();

        Optional<FactOrderBookLiquidityEntity> retrieved = factLiquidityRepository
                .findByDateIdAndTimeIdAndLocationIdAndTypeId(20260101, 1200, UPWELL_CITADEL_ID, TRITANIUM_TYPE_ID);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getLocationId()).isEqualTo(UPWELL_CITADEL_ID);
    }

    @Test
    @DisplayName("Should batch resolve uncatalogued locations from staging orders")
    void shouldBatchResolveMissingLocations() {
        Long citadel2 = 1030000000002L;
        Long citadel3 = 1030000000003L;

        StagingMarketOrderEntity order1 = StagingMarketOrderEntity.builder()
                .orderId(101L)
                .locationId(JITA_4_4_STATION_ID) // Known station
                .systemId(JITA_SYSTEM_ID)
                .regionId(THE_FORGE_REGION_ID)
                .build();

        StagingMarketOrderEntity order2 = StagingMarketOrderEntity.builder()
                .orderId(102L)
                .locationId(citadel2) // Unknown citadel
                .systemId(JITA_SYSTEM_ID)
                .regionId(THE_FORGE_REGION_ID)
                .build();

        StagingMarketOrderEntity order3 = StagingMarketOrderEntity.builder()
                .orderId(103L)
                .locationId(citadel3) // Another unknown citadel
                .systemId(JITA_SYSTEM_ID)
                .regionId(THE_FORGE_REGION_ID)
                .build();

        citadelDimensionResolver.resolveMissingLocations(List.of(order1, order2, order3));

        assertThat(dimStationRepository.existsById(citadel2)).isTrue();
        assertThat(dimStationRepository.existsById(citadel3)).isTrue();

        DimStationEntity station2 = dimStationRepository.findById(citadel2).orElseThrow();
        assertThat(station2.getIsStructure()).isTrue();
        assertThat(station2.getSolarSystemId()).isEqualTo(JITA_SYSTEM_ID);
    }
}
