package com.github.serbentd.eve.batch.partition;

import com.github.serbentd.eve.batch.config.BatchAmqpConfig;
import com.github.serbentd.eve.batch.domain.FactMarketDailySummaryEntity;
import com.github.serbentd.eve.batch.domain.FactOrderBookLiquidityEntity;
import com.github.serbentd.eve.batch.domain.StagingMarketOrderEntity;
import com.github.serbentd.eve.batch.job.daily.DailyMarketSummaryJobConfig;
import com.github.serbentd.eve.batch.job.liquidity.OrderBookLiquidityJobConfig;
import com.github.serbentd.eve.batch.repository.DimDateRepository;
import com.github.serbentd.eve.batch.repository.DimStationRepository;
import com.github.serbentd.eve.batch.repository.DimTimeRepository;
import com.github.serbentd.eve.batch.repository.FactMarketDailySummaryRepository;
import com.github.serbentd.eve.batch.repository.FactOrderBookLiquidityRepository;
import com.github.serbentd.eve.batch.repository.StagingMarketOrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.integration.partition.StepExecutionRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.messaging.MessageChannel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:eve_remote_partition_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "eve.batch.remote-partitioning.role=MANAGER",
        "eve.batch.remote-partitioning.amqp-flows-enabled=false",
        "eve.batch.remote-partitioning.poll-interval=50ms",
        "eve.batch.remote-partitioning.timeout=10s",
        "eve.batch.remote-partitioning.heavy-region-slices.10000002=2"
})
@Import(RemotePartitioningIntegrationTest.PartitionTestBridgeConfig.class)
class RemotePartitioningIntegrationTest {

    private static final Long THE_FORGE_REGION_ID = 10000002L;
    private static final Long DOMAIN_REGION_ID = 10000043L;
    private static final Long JITA_SYSTEM_ID = 30000142L;
    private static final Long AMARR_SYSTEM_ID = 30002187L;
    private static final Long JITA_4_4_STATION_ID = 60003760L;
    private static final Long AMARR_8_STATION_ID = 60008494L;
    private static final Long PERIMETER_CITADEL_ID = 1040000000099L;

    private static final Long TRITANIUM_TYPE_ID = 34L;
    private static final Long PLEX_TYPE_ID = 29668L;

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 11, 1);
    private static final String SNAPSHOT_TIME_STR = "2026-11-01T12:00:00Z";

    private final JobLauncher jobLauncher;
    private final Job dailyMarketSummaryJob;
    private final Job orderBookLiquidityJob;
    private final StagingMarketOrderRepository stagingRepository;
    private final FactMarketDailySummaryRepository dailyFactRepository;
    private final FactOrderBookLiquidityRepository liquidityFactRepository;
    private final DimDateRepository dimDateRepository;
    private final DimTimeRepository dimTimeRepository;
    private final DimStationRepository dimStationRepository;

    RemotePartitioningIntegrationTest(
            JobLauncher jobLauncher,
            @Qualifier(DailyMarketSummaryJobConfig.JOB_NAME) Job dailyMarketSummaryJob,
            @Qualifier(OrderBookLiquidityJobConfig.JOB_NAME) Job orderBookLiquidityJob,
            StagingMarketOrderRepository stagingRepository,
            FactMarketDailySummaryRepository dailyFactRepository,
            FactOrderBookLiquidityRepository liquidityFactRepository,
            DimDateRepository dimDateRepository,
            DimTimeRepository dimTimeRepository,
            DimStationRepository dimStationRepository) {

        this.jobLauncher = jobLauncher;
        this.dailyMarketSummaryJob = dailyMarketSummaryJob;
        this.orderBookLiquidityJob = orderBookLiquidityJob;
        this.stagingRepository = stagingRepository;
        this.dailyFactRepository = dailyFactRepository;
        this.liquidityFactRepository = liquidityFactRepository;
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
        dailyFactRepository.deleteAll();
        liquidityFactRepository.deleteAll();
        stagingRepository.deleteAll();
        dimStationRepository.deleteAll();
        dimTimeRepository.deleteAll();
        dimDateRepository.deleteAll();
    }

    @Test
    @DisplayName("Should execute dailyMarketSummaryJob across partitioned regions including The Forge item slices")
    void dailyMarketSummaryJob_withRemotePartitioning_shouldDistributeAndAggregatePartitions() throws Exception {
        Instant t0800 = TARGET_DATE.atTime(8, 0).toInstant(ZoneOffset.UTC);
        Instant t1400 = TARGET_DATE.atTime(14, 0).toInstant(ZoneOffset.UTC);

        // 1. The Forge: Tritanium (slice 0)
        StagingMarketOrderEntity forgeTrit1 = buildOrder(101L, THE_FORGE_REGION_ID, JITA_SYSTEM_ID, JITA_4_4_STATION_ID,
                TRITANIUM_TYPE_ID, new BigDecimal("4.00"), 1000L, true, t0800);
        StagingMarketOrderEntity forgeTrit2 = buildOrder(102L, THE_FORGE_REGION_ID, JITA_SYSTEM_ID, JITA_4_4_STATION_ID,
                TRITANIUM_TYPE_ID, new BigDecimal("5.00"), 2000L, false, t1400);

        // 2. The Forge: PLEX (slice 1)
        StagingMarketOrderEntity forgePlex = buildOrder(103L, THE_FORGE_REGION_ID, JITA_SYSTEM_ID, JITA_4_4_STATION_ID,
                PLEX_TYPE_ID, new BigDecimal("5000000.00"), 10L, false, t0800);

        // 3. Domain: Tritanium (standard single region partition)
        StagingMarketOrderEntity domainTrit = buildOrder(104L, DOMAIN_REGION_ID, AMARR_SYSTEM_ID, AMARR_8_STATION_ID,
                TRITANIUM_TYPE_ID, new BigDecimal("4.50"), 500L, true, t1400);

        stagingRepository.saveAll(List.of(forgeTrit1, forgeTrit2, forgePlex, domainTrit));

        JobParameters params = new JobParametersBuilder()
                .addString("targetDate", TARGET_DATE.toString())
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();

        JobExecution execution = jobLauncher.run(dailyMarketSummaryJob, params);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<FactMarketDailySummaryEntity> facts = dailyFactRepository.findAll();
        assertThat(facts).hasSize(3);

        // Verify The Forge Tritanium
        Optional<FactMarketDailySummaryEntity> forgeTritFact = facts.stream()
                .filter(f -> f.getRegionId().equals(THE_FORGE_REGION_ID) && f.getTypeId().equals(TRITANIUM_TYPE_ID))
                .findFirst();
        assertThat(forgeTritFact).isPresent();
        assertThat(forgeTritFact.get().getOrderCount()).isEqualTo(2);
        assertThat(forgeTritFact.get().getVolumeTraded()).isEqualTo(3000L);
        assertThat(forgeTritFact.get().getLowPrice()).isEqualByComparingTo("4.00");
        assertThat(forgeTritFact.get().getHighPrice()).isEqualByComparingTo("5.00");

        // Verify The Forge PLEX
        Optional<FactMarketDailySummaryEntity> forgePlexFact = facts.stream()
                .filter(f -> f.getRegionId().equals(THE_FORGE_REGION_ID) && f.getTypeId().equals(PLEX_TYPE_ID))
                .findFirst();
        assertThat(forgePlexFact).isPresent();
        assertThat(forgePlexFact.get().getOrderCount()).isEqualTo(1);
        assertThat(forgePlexFact.get().getVolumeTraded()).isEqualTo(10L);

        // Verify Domain Tritanium
        Optional<FactMarketDailySummaryEntity> domainTritFact = facts.stream()
                .filter(f -> f.getRegionId().equals(DOMAIN_REGION_ID) && f.getTypeId().equals(TRITANIUM_TYPE_ID))
                .findFirst();
        assertThat(domainTritFact).isPresent();
        assertThat(domainTritFact.get().getOrderCount()).isEqualTo(1);
        assertThat(domainTritFact.get().getVolumeTraded()).isEqualTo(500L);
    }

    @Test
    @DisplayName("Should execute orderBookLiquidityJob across partitioned regions and quantile slices with idempotent citadel resolution")
    void orderBookLiquidityJob_withRemotePartitioning_shouldDistributeAndAggregateLiquidity() throws Exception {
        Instant now = Instant.parse(SNAPSHOT_TIME_STR);

        // Active orders in The Forge: Tritanium (slice 0) and PLEX (slice 1) at the same uncatalogued Upwell citadel
        StagingMarketOrderEntity jitaBid = buildActiveOrder(201L, THE_FORGE_REGION_ID, JITA_SYSTEM_ID, PERIMETER_CITADEL_ID,
                TRITANIUM_TYPE_ID, new BigDecimal("4.10"), 1000L, true, now);
        StagingMarketOrderEntity jitaAsk = buildActiveOrder(202L, THE_FORGE_REGION_ID, JITA_SYSTEM_ID, PERIMETER_CITADEL_ID,
                TRITANIUM_TYPE_ID, new BigDecimal("4.30"), 2000L, false, now);
        StagingMarketOrderEntity citadelPlexAsk = buildActiveOrder(205L, THE_FORGE_REGION_ID, JITA_SYSTEM_ID, PERIMETER_CITADEL_ID,
                PLEX_TYPE_ID, new BigDecimal("5100000.00"), 25L, false, now);

        // Active orders in Domain (Amarr 8)
        StagingMarketOrderEntity amarrBid = buildActiveOrder(203L, DOMAIN_REGION_ID, AMARR_SYSTEM_ID, AMARR_8_STATION_ID,
                TRITANIUM_TYPE_ID, new BigDecimal("4.00"), 500L, true, now);
        StagingMarketOrderEntity amarrAsk = buildActiveOrder(204L, DOMAIN_REGION_ID, AMARR_SYSTEM_ID, AMARR_8_STATION_ID,
                TRITANIUM_TYPE_ID, new BigDecimal("4.50"), 800L, false, now);

        stagingRepository.saveAll(List.of(jitaBid, jitaAsk, citadelPlexAsk, amarrBid, amarrAsk));

        JobParameters params = new JobParametersBuilder()
                .addString("snapshotTime", SNAPSHOT_TIME_STR)
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();

        JobExecution execution = jobLauncher.run(orderBookLiquidityJob, params);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<FactOrderBookLiquidityEntity> facts = liquidityFactRepository.findAll();
        assertThat(facts).hasSize(3);
        assertThat(dimStationRepository.existsById(PERIMETER_CITADEL_ID)).isTrue();

        // Perimeter citadel Tritanium check (slice 0)
        Optional<FactOrderBookLiquidityEntity> jitaFact = facts.stream()
                .filter(f -> f.getLocationId().equals(PERIMETER_CITADEL_ID) && f.getTypeId().equals(TRITANIUM_TYPE_ID))
                .findFirst();
        assertThat(jitaFact).isPresent();
        assertThat(jitaFact.get().getBestBid()).isEqualByComparingTo("4.10");
        assertThat(jitaFact.get().getBestAsk()).isEqualByComparingTo("4.30");
        assertThat(jitaFact.get().getSpread()).isEqualByComparingTo("0.20");

        // Perimeter citadel PLEX check (slice 1)
        Optional<FactOrderBookLiquidityEntity> plexFact = facts.stream()
                .filter(f -> f.getLocationId().equals(PERIMETER_CITADEL_ID) && f.getTypeId().equals(PLEX_TYPE_ID))
                .findFirst();
        assertThat(plexFact).isPresent();
        assertThat(plexFact.get().getBestAsk()).isEqualByComparingTo("5100000.00");
        assertThat(plexFact.get().getSellVolume()).isEqualTo(25L);

        // Amarr check
        Optional<FactOrderBookLiquidityEntity> amarrFact = facts.stream()
                .filter(f -> f.getLocationId().equals(AMARR_8_STATION_ID))
                .findFirst();
        assertThat(amarrFact).isPresent();
        assertThat(amarrFact.get().getBestBid()).isEqualByComparingTo("4.00");
        assertThat(amarrFact.get().getBestAsk()).isEqualByComparingTo("4.50");
        assertThat(amarrFact.get().getSpread()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("Should reject job execution when required temporal job parameters are missing or malformed")
    void jobs_whenTemporalParametersMissingOrMalformed_shouldThrowJobParametersInvalidException() {
        JobParameters missingParams = new JobParametersBuilder()
                .addLong("run.id", 1L)
                .toJobParameters();

        assertThatThrownBy(() -> jobLauncher.run(dailyMarketSummaryJob, missingParams))
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining("targetDate");

        assertThatThrownBy(() -> jobLauncher.run(orderBookLiquidityJob, missingParams))
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining("snapshotTime");

        JobParameters malformedDailyParams = new JobParametersBuilder()
                .addString("targetDate", "not-a-date")
                .toJobParameters();

        assertThatThrownBy(() -> jobLauncher.run(dailyMarketSummaryJob, malformedDailyParams))
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining("ISO-8601 LocalDate");

        JobParameters malformedLiquidityParams = new JobParametersBuilder()
                .addString("snapshotTime", "not-an-instant")
                .toJobParameters();

        assertThatThrownBy(() -> jobLauncher.run(orderBookLiquidityJob, malformedLiquidityParams))
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining("ISO-8601 Instant");
    }

    private StagingMarketOrderEntity buildOrder(
            Long orderId, Long regionId, Long systemId, Long locationId,
            Long typeId, BigDecimal price, Long volume, Boolean isBuy, Instant issued) {

        return StagingMarketOrderEntity.builder()
                .orderId(orderId)
                .regionId(regionId)
                .systemId(systemId)
                .locationId(locationId)
                .typeId(typeId)
                .price(price)
                .volumeRemain(volume)
                .volumeTotal(volume)
                .minVolume(1)
                .duration(90)
                .isBuyOrder(isBuy)
                .orderRange("region")
                .status("ACTIVE")
                .isActive(true)
                .issued(issued)
                .createdAt(issued)
                .updatedAt(issued)
                .build();
    }

    private StagingMarketOrderEntity buildActiveOrder(
            Long orderId, Long regionId, Long systemId, Long locationId,
            Long typeId, BigDecimal price, Long volume, Boolean isBuy, Instant now) {

        return buildOrder(orderId, regionId, systemId, locationId, typeId, price, volume, isBuy, now);
    }

    @TestConfiguration
    static class PartitionTestBridgeConfig {

        @Bean
        public IntegrationFlow testPartitionBridgeFlow(
                @Qualifier(BatchAmqpConfig.OUTBOUND_CHANNEL) MessageChannel outboundChannel,
                MessageConverter batchJsonMessageConverter) {

            return IntegrationFlow.from(outboundChannel)
                    .transform(request -> {
                        Message message = batchJsonMessageConverter.toMessage(request, new MessageProperties());
                        return (StepExecutionRequest) batchJsonMessageConverter.fromMessage(message);
                    })
                    .route(StepExecutionRequest.class, StepExecutionRequest::getStepName, mapping -> mapping
                            .channelMapping(DailyMarketSummaryJobConfig.WORKER_STEP_NAME, BatchAmqpConfig.DAILY_INBOUND_CHANNEL)
                            .channelMapping(OrderBookLiquidityJobConfig.WORKER_STEP_NAME, BatchAmqpConfig.LIQUIDITY_INBOUND_CHANNEL))
                    .get();
        }
    }
}
