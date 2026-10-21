package com.github.serbentd.eve.batch.job.daily;

import com.github.serbentd.eve.batch.domain.FactMarketDailySummaryEntity;
import com.github.serbentd.eve.batch.domain.StagingMarketOrderEntity;
import com.github.serbentd.eve.batch.repository.DimDateRepository;
import com.github.serbentd.eve.batch.repository.FactMarketDailySummaryRepository;
import com.github.serbentd.eve.batch.repository.StagingMarketOrderRepository;
import com.github.serbentd.eve.batch.service.DateDimensionService;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DailyMarketSummaryJobIntegrationTest {

    private static final Long THE_FORGE_REGION_ID = 10000002L;
    private static final Long JITA_SYSTEM_ID = 30000142L;
    private static final Long JITA_4_4_STATION_ID = 60003760L;
    private static final Long TRITANIUM_TYPE_ID = 34L;
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 10, 10);
    private static final Integer EXPECTED_DATE_ID = 20261010;

    private final JobLauncher jobLauncher;
    private final Job dailyMarketSummaryJob;
    private final StagingMarketOrderRepository stagingRepository;
    private final FactMarketDailySummaryRepository factRepository;
    private final DateDimensionService dateDimensionService;
    private final DimDateRepository dimDateRepository;

    DailyMarketSummaryJobIntegrationTest(
            JobLauncher jobLauncher,
            @Qualifier(DailyMarketSummaryJobConfig.JOB_NAME) Job dailyMarketSummaryJob,
            StagingMarketOrderRepository stagingRepository,
            FactMarketDailySummaryRepository factRepository,
            DateDimensionService dateDimensionService,
            DimDateRepository dimDateRepository) {
        this.jobLauncher = jobLauncher;
        this.dailyMarketSummaryJob = dailyMarketSummaryJob;
        this.stagingRepository = stagingRepository;
        this.factRepository = factRepository;
        this.dateDimensionService = dateDimensionService;
        this.dimDateRepository = dimDateRepository;
    }

    @BeforeEach
    void setUp() {
        stagingRepository.deleteAll();
        factRepository.deleteAll();
        dimDateRepository.deleteAll();
        dateDimensionService.ensureDateExists(TARGET_DATE);
    }

    @AfterEach
    void tearDown() {
        stagingRepository.deleteAll();
        factRepository.deleteAll();
        dimDateRepository.deleteAll();
    }

    @Test
    @DisplayName("Should aggregate staging market orders into fact_market_daily_summary with OHLC and additive metrics")
    void dailyMarketSummaryJob_shouldComputeAndPersistAggregates() throws Exception {
        // Arrange: 3 orders issued at different hours on 2026-10-10
        Instant t0800 = TARGET_DATE.atTime(8, 0).toInstant(ZoneOffset.UTC);
        Instant t1200 = TARGET_DATE.atTime(12, 0).toInstant(ZoneOffset.UTC);
        Instant t1800 = TARGET_DATE.atTime(18, 0).toInstant(ZoneOffset.UTC);

        StagingMarketOrderEntity order1 = buildOrder(1001L, new BigDecimal("4.50"), 1000L, t0800);
        StagingMarketOrderEntity order2 = buildOrder(1002L, new BigDecimal("5.00"), 2000L, t1200);
        StagingMarketOrderEntity order3 = buildOrder(1003L, new BigDecimal("4.20"), 500L, t1800);

        stagingRepository.saveAll(List.of(order1, order2, order3));

        JobParameters params = new JobParametersBuilder()
                .addString("targetDate", TARGET_DATE.toString())
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();

        // Act
        JobExecution execution = jobLauncher.run(dailyMarketSummaryJob, params);

        // Assert
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Optional<FactMarketDailySummaryEntity> factOpt = factRepository
                .findByDateIdAndRegionIdAndTypeId(EXPECTED_DATE_ID, THE_FORGE_REGION_ID, TRITANIUM_TYPE_ID);

        assertThat(factOpt).isPresent();
        FactMarketDailySummaryEntity fact = factOpt.get();

        assertThat(fact.getDateId()).isEqualTo(EXPECTED_DATE_ID);
        assertThat(fact.getRegionId()).isEqualTo(THE_FORGE_REGION_ID);
        assertThat(fact.getTypeId()).isEqualTo(TRITANIUM_TYPE_ID);

        // Open = 4.50 (earliest at 08:00), Close = 4.20 (latest at 18:00), High = 5.00, Low = 4.20
        assertThat(fact.getOpenPrice()).isEqualByComparingTo("4.50");
        assertThat(fact.getHighPrice()).isEqualByComparingTo("5.00");
        assertThat(fact.getLowPrice()).isEqualByComparingTo("4.20");
        assertThat(fact.getClosePrice()).isEqualByComparingTo("4.20");

        // Total volume = 1000 + 2000 + 500 = 3500
        assertThat(fact.getVolumeTraded()).isEqualTo(3500L);

        // Turnover ISK = (4.50 * 1000) + (5.00 * 2000) + (4.20 * 500) = 4500 + 10000 + 2100 = 16600.00
        assertThat(fact.getTurnoverIsk()).isEqualByComparingTo("16600.00");

        assertThat(fact.getOrderCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should be idempotent and update existing summary when re-run for same date")
    void dailyMarketSummaryJob_shouldBeIdempotentOnRerun() throws Exception {
        // Arrange
        Instant t1000 = TARGET_DATE.atTime(10, 0).toInstant(ZoneOffset.UTC);
        StagingMarketOrderEntity initialOrder = buildOrder(2001L, new BigDecimal("4.00"), 1000L, t1000);
        stagingRepository.save(initialOrder);

        JobParameters params1 = new JobParametersBuilder()
                .addString("targetDate", TARGET_DATE.toString())
                .addLong("run.id", 1L)
                .toJobParameters();

        JobExecution run1 = jobLauncher.run(dailyMarketSummaryJob, params1);
        assertThat(run1.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Re-run with updated order volume
        initialOrder.setVolumeRemain(3000L);
        stagingRepository.save(initialOrder);

        JobParameters params2 = new JobParametersBuilder()
                .addString("targetDate", TARGET_DATE.toString())
                .addLong("run.id", 2L)
                .toJobParameters();

        JobExecution run2 = jobLauncher.run(dailyMarketSummaryJob, params2);
        assertThat(run2.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert: Exactly 1 record exists with updated volume
        List<FactMarketDailySummaryEntity> allFacts = factRepository.findAll();
        assertThat(allFacts).hasSize(1);
        assertThat(allFacts.getFirst().getVolumeTraded()).isEqualTo(3000L);
    }

    private StagingMarketOrderEntity buildOrder(Long orderId, BigDecimal price, Long volumeRemain, Instant issued) {
        return StagingMarketOrderEntity.builder()
                .orderId(orderId)
                .typeId(TRITANIUM_TYPE_ID)
                .regionId(THE_FORGE_REGION_ID)
                .systemId(JITA_SYSTEM_ID)
                .locationId(JITA_4_4_STATION_ID)
                .price(price)
                .volumeRemain(volumeRemain)
                .volumeTotal(volumeRemain)
                .minVolume(1)
                .duration(90)
                .isBuyOrder(false)
                .orderRange("region")
                .status("ACTIVE")
                .isActive(true)
                .issued(issued)
                .updatedAt(issued)
                .build();
    }
}
