package com.github.serbentd.eve.batch.job.liquidity;

import com.github.serbentd.eve.batch.config.BatchAmqpConfig;
import com.github.serbentd.eve.batch.config.BatchProperties;
import com.github.serbentd.eve.batch.domain.FactOrderBookLiquidityEntity;
import com.github.serbentd.eve.batch.dto.OrderBookLiquidityAggregationDto;
import com.github.serbentd.eve.batch.partition.RegionPartitioner;
import com.github.serbentd.eve.batch.reaper.AdvisoryLockJobExecutionListener;
import com.github.serbentd.eve.batch.repository.StagingMarketOrderRepository;
import com.github.serbentd.eve.batch.service.DateDimensionService;
import com.github.serbentd.eve.batch.service.TimeDimensionService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.integration.partition.RemotePartitioningManagerStepBuilderFactory;
import org.springframework.batch.integration.partition.RemotePartitioningWorkerStepBuilderFactory;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Spring Batch 5 configuration for {@code orderBookLiquidityJob}.
 * Aggregates active staging market orders into conformed periodic snapshot records
 * in {@code analytics.fact_order_book_liquidity} with spread and volume depth,
 * supporting optional distributed remote partitioning across market regions.
 */
@Configuration
public class OrderBookLiquidityJobConfig {

    public static final String JOB_NAME = "orderBookLiquidityJob";
    public static final String STEP_NAME = "orderBookLiquidityStep";
    public static final String MANAGER_STEP_NAME = "orderBookLiquidityManagerStep";
    public static final String WORKER_STEP_NAME = "orderBookLiquidityWorkerStep";

    public static final String SNAPSHOT_TIME_PARAM = "snapshotTime";
    public static final String SNAPSHOT_TIME_EXPRESSION = "#{jobParameters['" + SNAPSHOT_TIME_PARAM + "']}";

    private static final String LIQUIDITY_AGGREGATION_SQL = buildLiquidityAggregationSql(
            "is_active = TRUE AND status = 'ACTIVE'");

    private static final String LIQUIDITY_PARTITION_AGGREGATION_SQL = buildLiquidityAggregationSql(
            "is_active = TRUE AND status = 'ACTIVE' AND region_id = ? AND type_id >= ? AND type_id < ?");

    private static String buildLiquidityAggregationSql(String wherePredicate) {
        return """
                SELECT
                    location_id,
                    type_id,
                    MAX(system_id) AS system_id,
                    MAX(region_id) AS region_id,
                    MAX(CASE WHEN is_buy_order = TRUE THEN price ELSE NULL END) AS best_bid,
                    MIN(CASE WHEN is_buy_order = FALSE THEN price ELSE NULL END) AS best_ask,
                    COALESCE(SUM(CASE WHEN is_buy_order = TRUE THEN volume_remain ELSE 0 END), 0) AS buy_volume,
                    COALESCE(SUM(CASE WHEN is_buy_order = FALSE THEN volume_remain ELSE 0 END), 0) AS sell_volume,
                    COALESCE(SUM(CASE WHEN is_buy_order = TRUE THEN 1 ELSE 0 END), 0) AS buy_orders_count,
                    COALESCE(SUM(CASE WHEN is_buy_order = FALSE THEN 1 ELSE 0 END), 0) AS sell_orders_count
                FROM staging.market_orders
                WHERE %s
                GROUP BY location_id, type_id
                ORDER BY location_id, type_id
                """.formatted(wherePredicate);
    }

    static JobParametersValidator liquiditySnapshotTimeValidator() {
        return parameters -> {
            String snapshotTimeStr = parameters != null ? parameters.getString(SNAPSHOT_TIME_PARAM) : null;
            if (snapshotTimeStr == null || snapshotTimeStr.isBlank()) {
                throw new JobParametersInvalidException(
                        "Required job parameter '" + SNAPSHOT_TIME_PARAM + "' (ISO-8601 Instant) is missing");
            }
            try {
                Instant.parse(snapshotTimeStr);
            } catch (DateTimeParseException e) {
                throw new JobParametersInvalidException(
                        "Job parameter '" + SNAPSHOT_TIME_PARAM + "' must be a valid ISO-8601 Instant, got: " + snapshotTimeStr);
            }
        };
    }

    @Bean
    @StepScope
    public JdbcCursorItemReader<OrderBookLiquidityAggregationDto> orderBookLiquidityReader(
            DataSource dataSource,
            BatchProperties batchProperties) {

        return new JdbcCursorItemReaderBuilder<OrderBookLiquidityAggregationDto>()
                .name("orderBookLiquidityReader")
                .dataSource(dataSource)
                .sql(LIQUIDITY_AGGREGATION_SQL)
                .rowMapper(new OrderBookLiquidityRowMapper())
                .fetchSize(batchProperties.chunkSize().orderBookLiquidity())
                .saveState(true)
                .build();
    }

    @Bean
    @StepScope
    public JdbcCursorItemReader<OrderBookLiquidityAggregationDto> orderBookLiquidityPartitionReader(
            DataSource dataSource,
            @Value("#{stepExecutionContext['" + RegionPartitioner.CONTEXT_REGION_ID + "']}") Long regionId,
            @Value("#{stepExecutionContext['" + RegionPartitioner.CONTEXT_MIN_TYPE_ID + "']}") Long minTypeId,
            @Value("#{stepExecutionContext['" + RegionPartitioner.CONTEXT_MAX_TYPE_ID + "']}") Long maxTypeId,
            BatchProperties batchProperties) {

        // Fail fast: a worker must never silently aggregate an unbounded or wrong partition
        long partitionRegionId = Objects.requireNonNull(regionId, "Partition context is missing regionId");
        long partitionMinTypeId = Objects.requireNonNull(minTypeId, "Partition context is missing minTypeId");
        long partitionMaxTypeId = Objects.requireNonNull(maxTypeId, "Partition context is missing maxTypeId");

        return new JdbcCursorItemReaderBuilder<OrderBookLiquidityAggregationDto>()
                .name("orderBookLiquidityPartitionReader")
                .dataSource(dataSource)
                .sql(LIQUIDITY_PARTITION_AGGREGATION_SQL)
                .preparedStatementSetter(ps -> {
                    ps.setLong(1, partitionRegionId);
                    ps.setLong(2, partitionMinTypeId);
                    ps.setLong(3, partitionMaxTypeId);
                })
                .rowMapper(new OrderBookLiquidityRowMapper())
                .fetchSize(batchProperties.chunkSize().orderBookLiquidity())
                .saveState(false)
                .build();
    }

    @Bean
    @StepScope
    public RegionPartitioner liquidityRegionPartitioner(
            StagingMarketOrderRepository stagingMarketOrderRepository,
            DateDimensionService dateDimensionService,
            TimeDimensionService timeDimensionService,
            @Value(SNAPSHOT_TIME_EXPRESSION) String snapshotTimeStr,
            BatchProperties batchProperties) {

        Instant snapshotInstant = Instant.parse(Objects.requireNonNull(snapshotTimeStr, "Job parameter 'snapshotTime' is required"));
        LocalDate date = snapshotInstant.atZone(ZoneOffset.UTC).toLocalDate();
        LocalTime time = snapshotInstant.atZone(ZoneOffset.UTC).toLocalTime();

        return new RegionPartitioner(
                stagingMarketOrderRepository::findDistinctActiveRegionIds,
                batchProperties.remotePartitioning().heavyRegionSlices(),
                stagingMarketOrderRepository::findTypeIdQuantileBoundaries,
                () -> {
                    dateDimensionService.ensureDateExists(date);
                    timeDimensionService.ensureTimeExists(time);
                }
        );
    }

    @Bean(name = STEP_NAME)
    public Step orderBookLiquidityStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            @Qualifier("orderBookLiquidityReader") JdbcCursorItemReader<OrderBookLiquidityAggregationDto> orderBookLiquidityReader,
            OrderBookLiquidityProcessor processor,
            OrderBookLiquidityWriter writer,
            BatchProperties batchProperties) {

        return new StepBuilder(STEP_NAME, jobRepository)
                .<OrderBookLiquidityAggregationDto, FactOrderBookLiquidityEntity>chunk(batchProperties.chunkSize().orderBookLiquidity(), transactionManager)
                .reader(orderBookLiquidityReader)
                .processor(processor)
                .writer(writer)
                .build();
    }

    @Bean(name = WORKER_STEP_NAME)
    public Step orderBookLiquidityWorkerStep(
            RemotePartitioningWorkerStepBuilderFactory workerStepBuilderFactory,
            PlatformTransactionManager transactionManager,
            @Qualifier("orderBookLiquidityPartitionReader") JdbcCursorItemReader<OrderBookLiquidityAggregationDto> orderBookLiquidityPartitionReader,
            OrderBookLiquidityProcessor processor,
            OrderBookLiquidityWriter writer,
            @Qualifier(BatchAmqpConfig.LIQUIDITY_INBOUND_CHANNEL) MessageChannel liquidityPartitionRequestsInboundChannel,
            BatchProperties batchProperties,
            JobExplorer jobExplorer) {

        return workerStepBuilderFactory.get(WORKER_STEP_NAME)
                .inputChannel(liquidityPartitionRequestsInboundChannel)
                .jobExplorer(jobExplorer)
                .<OrderBookLiquidityAggregationDto, FactOrderBookLiquidityEntity>chunk(batchProperties.chunkSize().orderBookLiquidity(), transactionManager)
                .reader(orderBookLiquidityPartitionReader)
                .processor(processor)
                .writer(writer)
                .build();
    }

    @Bean(name = MANAGER_STEP_NAME)
    public Step orderBookLiquidityManagerStep(
            RemotePartitioningManagerStepBuilderFactory managerStepBuilderFactory,
            @Qualifier("liquidityRegionPartitioner") RegionPartitioner liquidityRegionPartitioner,
            @Qualifier(BatchAmqpConfig.OUTBOUND_CHANNEL) MessageChannel partitionRequestsOutboundChannel,
            JobExplorer jobExplorer,
            BatchProperties batchProperties) {

        return managerStepBuilderFactory.get(MANAGER_STEP_NAME)
                .partitioner(WORKER_STEP_NAME, liquidityRegionPartitioner)
                .outputChannel(partitionRequestsOutboundChannel)
                .jobExplorer(jobExplorer)
                .pollInterval(batchProperties.remotePartitioning().pollInterval().toMillis())
                .timeout(batchProperties.remotePartitioning().timeout().toMillis())
                .build();
    }

    @Bean
    public Job orderBookLiquidityJob(
            JobRepository jobRepository,
            @Qualifier(STEP_NAME) Step orderBookLiquidityStep,
            @Qualifier(MANAGER_STEP_NAME) Step orderBookLiquidityManagerStep,
            AdvisoryLockJobExecutionListener advisoryLockListener,
            BatchProperties batchProperties) {

        Step executionStep = batchProperties.remotePartitioning().isPartitioned()
                ? orderBookLiquidityManagerStep
                : orderBookLiquidityStep;

        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(liquiditySnapshotTimeValidator())
                .start(executionStep)
                .listener(advisoryLockListener)
                .build();
    }
}
