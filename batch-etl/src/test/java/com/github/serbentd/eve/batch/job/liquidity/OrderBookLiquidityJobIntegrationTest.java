package com.github.serbentd.eve.batch.job.liquidity;

import com.github.serbentd.eve.batch.domain.FactOrderBookLiquidityEntity;
import com.github.serbentd.eve.batch.domain.StagingMarketOrderEntity;
import com.github.serbentd.eve.batch.repository.DimDateRepository;
import com.github.serbentd.eve.batch.repository.DimStationRepository;
import com.github.serbentd.eve.batch.repository.DimTimeRepository;
import com.github.serbentd.eve.batch.repository.FactOrderBookLiquidityRepository;
import com.github.serbentd.eve.batch.repository.StagingMarketOrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderBookLiquidityJobIntegrationTest {

    private static final Long THE_FORGE_REGION_ID = 10000002L;
    private static final Long JITA_SYSTEM_ID = 30000142L;
    private static final Long JITA_4_4_STATION_ID = 60003760L;
    private static final Long UPWELL_CITADEL_ID = 1040000000001L;
    private static final Long TRITANIUM_TYPE_ID = 34L;
    private static final Long PLEX_TYPE_ID = 29668L;

    private static final String SNAPSHOT_TIME_STR = "2026-10-12T14:30:00Z";
    private static final Integer EXPECTED_DATE_ID = 20261012;
    private static final Integer EXPECTED_TIME_ID = 1430;

    private final JobLauncher jobLauncher;
    private final Job orderBookLiquidityJob;
    private final StagingMarketOrderRepository stagingRepository;
    private final FactOrderBookLiquidityRepository factRepository;
    private final DimDateRepository dimDateRepository;
    private final DimTimeRepository dimTimeRepository;
    private final DimStationRepository dimStationRepository;

    OrderBookLiquidityJobIntegrationTest(
            JobLauncher jobLauncher,
            @Qualifier(OrderBookLiquidityJobConfig.JOB_NAME) Job orderBookLiquidityJob,
            StagingMarketOrderRepository stagingRepository,
            FactOrderBookLiquidityRepository factRepository,
            DimDateRepository dimDateRepository,
            DimTimeRepository dimTimeRepository,
            DimStationRepository dimStationRepository) {
        this.jobLauncher = jobLauncher;
        this.orderBookLiquidityJob = orderBookLiquidityJob;
        this.stagingRepository = stagingRepository;
        this.factRepository = factRepository;
        this.dimDateRepository = dimDateRepository;
        this.dimTimeRepository = dimTimeRepository;
        this.dimStationRepository = dimStationRepository;
    }

    @BeforeEach
    void setUp() {
        cleanData();
    }

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void cleanData() {
        stagingRepository.deleteAll();
        factRepository.deleteAll();
        dimDateRepository.deleteAll();
        dimTimeRepository.deleteAll();
        if (dimStationRepository.existsById(UPWELL_CITADEL_ID)) {
            dimStationRepository.deleteById(UPWELL_CITADEL_ID);
        }
    }

    @Test
    @DisplayName("Should aggregate active orders into order book liquidity fact with best bid, best ask, and spread")
    void orderBookLiquidityJob_shouldComputeLiquidityDepthAndSpread() throws Exception {
        Instant now = Instant.parse(SNAPSHOT_TIME_STR);

        // Arrange: Tritanium in Jita 4-4
        // Buy 1: 4.50 (vol 1000)
        // Buy 2: 4.80 (vol 500) -> Best Bid = 4.80, Buy Vol = 1500, Buy Count = 2
        // Sell 1: 5.20 (vol 2000) -> Best Ask = 5.20, Sell Vol = 2000, Sell Count = 1
        // Expected spread = 5.20 - 4.80 = 0.40
        StagingMarketOrderEntity buy1 = buildOrder(101L, TRITANIUM_TYPE_ID, JITA_4_4_STATION_ID, new BigDecimal("4.50"), 1000L, true, true, "ACTIVE", now);
        StagingMarketOrderEntity buy2 = buildOrder(102L, TRITANIUM_TYPE_ID, JITA_4_4_STATION_ID, new BigDecimal("4.80"), 500L, true, true, "ACTIVE", now);
        StagingMarketOrderEntity sell1 = buildOrder(103L, TRITANIUM_TYPE_ID, JITA_4_4_STATION_ID, new BigDecimal("5.20"), 2000L, false, true, "ACTIVE", now);

        // Inactive order: should be excluded
        StagingMarketOrderEntity expiredOrder = buildOrder(104L, TRITANIUM_TYPE_ID, JITA_4_4_STATION_ID, new BigDecimal("6.00"), 5000L, false, false, "EXPIRED", now);

        stagingRepository.saveAll(List.of(buy1, buy2, sell1, expiredOrder));

        JobParameters params = new JobParametersBuilder()
                .addString("snapshotTime", SNAPSHOT_TIME_STR)
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();

        // Act
        JobExecution execution = jobLauncher.run(orderBookLiquidityJob, params);

        // Assert
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Optional<FactOrderBookLiquidityEntity> factOpt = factRepository
                .findByDateIdAndTimeIdAndLocationIdAndTypeId(EXPECTED_DATE_ID, EXPECTED_TIME_ID, JITA_4_4_STATION_ID, TRITANIUM_TYPE_ID);

        assertThat(factOpt).isPresent();
        FactOrderBookLiquidityEntity fact = factOpt.get();

        assertThat(fact.getDateId()).isEqualTo(EXPECTED_DATE_ID);
        assertThat(fact.getTimeId()).isEqualTo(EXPECTED_TIME_ID);
        assertThat(fact.getLocationId()).isEqualTo(JITA_4_4_STATION_ID);
        assertThat(fact.getTypeId()).isEqualTo(TRITANIUM_TYPE_ID);

        assertThat(fact.getBestBid()).isEqualByComparingTo("4.80");
        assertThat(fact.getBestAsk()).isEqualByComparingTo("5.20");
        assertThat(fact.getSpread()).isEqualByComparingTo("0.40");

        assertThat(fact.getBuyVolume()).isEqualTo(1500L);
        assertThat(fact.getSellVolume()).isEqualTo(2000L);
        assertThat(fact.getBuyOrdersCount()).isEqualTo(2);
        assertThat(fact.getSellOrdersCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should resolve late-arriving Upwell citadel stubs automatically and handle one-sided order books")
    void orderBookLiquidityJob_shouldResolveCitadelAndHandleOneSidedBooks() throws Exception {
        Instant now = Instant.parse(SNAPSHOT_TIME_STR);

        // Arrange: PLEX order at uncatalogued Upwell Citadel with only sell orders
        StagingMarketOrderEntity sellOnly = buildOrder(201L, PLEX_TYPE_ID, UPWELL_CITADEL_ID, new BigDecimal("5100000.00"), 50L, false, true, "ACTIVE", now);
        stagingRepository.save(sellOnly);

        JobParameters params = new JobParametersBuilder()
                .addString("snapshotTime", SNAPSHOT_TIME_STR)
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();

        // Act
        JobExecution execution = jobLauncher.run(orderBookLiquidityJob, params);

        // Assert
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify late-arriving citadel stub was created in dim_station
        assertThat(dimStationRepository.existsById(UPWELL_CITADEL_ID)).isTrue();

        Optional<FactOrderBookLiquidityEntity> factOpt = factRepository
                .findByDateIdAndTimeIdAndLocationIdAndTypeId(EXPECTED_DATE_ID, EXPECTED_TIME_ID, UPWELL_CITADEL_ID, PLEX_TYPE_ID);

        assertThat(factOpt).isPresent();
        FactOrderBookLiquidityEntity fact = factOpt.get();

        assertThat(fact.getBestBid()).isNull();
        assertThat(fact.getBestAsk()).isEqualByComparingTo("5100000.00");
        assertThat(fact.getSpread()).isNull();
        assertThat(fact.getBuyVolume()).isZero();
        assertThat(fact.getSellVolume()).isEqualTo(50L);
        assertThat(fact.getBuyOrdersCount()).isZero();
        assertThat(fact.getSellOrdersCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should be idempotent and update existing snapshot on rerun for same snapshotTime")
    void orderBookLiquidityJob_shouldBeIdempotentOnRerun() throws Exception {
        Instant now = Instant.parse(SNAPSHOT_TIME_STR);

        StagingMarketOrderEntity order = buildOrder(301L, TRITANIUM_TYPE_ID, JITA_4_4_STATION_ID, new BigDecimal("5.00"), 1000L, false, true, "ACTIVE", now);
        stagingRepository.save(order);

        JobParameters params1 = new JobParametersBuilder()
                .addString("snapshotTime", SNAPSHOT_TIME_STR)
                .addLong("run.id", 1L)
                .toJobParameters();

        JobExecution run1 = jobLauncher.run(orderBookLiquidityJob, params1);
        assertThat(run1.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Update volume in staging order
        order.setVolumeRemain(4500L);
        stagingRepository.save(order);

        JobParameters params2 = new JobParametersBuilder()
                .addString("snapshotTime", SNAPSHOT_TIME_STR)
                .addLong("run.id", 2L)
                .toJobParameters();

        JobExecution run2 = jobLauncher.run(orderBookLiquidityJob, params2);
        assertThat(run2.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<FactOrderBookLiquidityEntity> allFacts = factRepository.findAll();
        assertThat(allFacts).hasSize(1);
        assertThat(allFacts.getFirst().getSellVolume()).isEqualTo(4500L);
    }

    private StagingMarketOrderEntity buildOrder(
            Long orderId,
            Long typeId,
            Long locationId,
            BigDecimal price,
            Long volumeRemain,
            boolean isBuy,
            boolean isActive,
            String status,
            Instant issued) {

        return StagingMarketOrderEntity.builder()
                .orderId(orderId)
                .typeId(typeId)
                .regionId(THE_FORGE_REGION_ID)
                .systemId(JITA_SYSTEM_ID)
                .locationId(locationId)
                .price(price)
                .volumeRemain(volumeRemain)
                .volumeTotal(volumeRemain)
                .minVolume(1)
                .duration(90)
                .isBuyOrder(isBuy)
                .orderRange("region")
                .status(status)
                .isActive(isActive)
                .issued(issued)
                .updatedAt(issued)
                .build();
    }
}
