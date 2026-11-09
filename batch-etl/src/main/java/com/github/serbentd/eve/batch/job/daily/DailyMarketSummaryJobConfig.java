package com.github.serbentd.eve.batch.job.daily;

import com.github.serbentd.eve.batch.config.BatchAmqpConfig;
import com.github.serbentd.eve.batch.config.BatchProperties;
import com.github.serbentd.eve.batch.domain.FactMarketDailySummaryEntity;
import com.github.serbentd.eve.batch.dto.MarketDailyAggregationDto;
import com.github.serbentd.eve.batch.partition.RegionPartitioner;
import com.github.serbentd.eve.batch.reaper.AdvisoryLockJobExecutionListener;
import com.github.serbentd.eve.batch.repository.StagingMarketOrderRepository;
import com.github.serbentd.eve.batch.service.DateDimensionService;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Spring Batch 5 configuration for {@code dailyMarketSummaryJob}.
 * Aggregates raw staging market orders into conformed periodic daily snapshots
 * using streaming JDBC cursors, optional remote partitioning across market regions,
 * and atomic SQL batch upserts.
 */
@Configuration
public class DailyMarketSummaryJobConfig {

    public static final String JOB_NAME = "dailyMarketSummaryJob";
    public static final String STEP_NAME = "dailyMarketSummaryStep";
    public static final String MANAGER_STEP_NAME = "dailyMarketSummaryManagerStep";
    public static final String WORKER_STEP_NAME = "dailyMarketSummaryWorkerStep";

    public static final String TARGET_DATE_PARAM = "targetDate";
    public static final String TARGET_DATE_EXPRESSION = "#{jobParameters['" + TARGET_DATE_PARAM + "']}";

    private static final String DAILY_AGGREGATION_SQL = buildDailyAggregationSql(
            "issued >= ? AND issued < ?");

    private static final String DAILY_PARTITION_AGGREGATION_SQL = buildDailyAggregationSql(
            "region_id = ? AND type_id >= ? AND type_id < ? AND issued >= ? AND issued < ?");

    private static String buildDailyAggregationSql(String wherePredicate) {
        return """
                WITH ranked_orders AS (
                    SELECT 
                        region_id,
                        type_id,
                        price,
                        volume_remain,
                        issued,
                        FIRST_VALUE(price) OVER (PARTITION BY region_id, type_id ORDER BY issued ASC) AS open_price,
                        FIRST_VALUE(price) OVER (PARTITION BY region_id, type_id ORDER BY issued DESC) AS close_price
                    FROM staging.market_orders
                    WHERE %s
                )
                SELECT 
                    region_id,
                    type_id,
                    MIN(price) AS low_price,
                    MAX(price) AS high_price,
                    SUM(volume_remain) AS volume_traded,
                    SUM(price * volume_remain) AS turnover_isk,
                    COUNT(*) AS order_count,
                    MIN(open_price) AS open_price,
                    MIN(close_price) AS close_price
                FROM ranked_orders
                GROUP BY region_id, type_id
                ORDER BY region_id, type_id
                """.formatted(wherePredicate);
    }

    static JobParametersValidator dailyTargetDateValidator() {
        return parameters -> {
            String targetDateStr = parameters != null ? parameters.getString(TARGET_DATE_PARAM) : null;
            if (targetDateStr == null || targetDateStr.isBlank()) {
                throw new JobParametersInvalidException(
                        "Required job parameter '" + TARGET_DATE_PARAM + "' (ISO-8601 LocalDate) is missing");
            }
            try {
                LocalDate.parse(targetDateStr);
            } catch (DateTimeParseException e) {
                throw new JobParametersInvalidException(
                        "Job parameter '" + TARGET_DATE_PARAM + "' must be a valid ISO-8601 LocalDate, got: " + targetDateStr);
            }
        };
    }

    @Bean
    @StepScope
    public JdbcCursorItemReader<MarketDailyAggregationDto> dailyMarketOrderReader(
            DataSource dataSource,
            @Value(TARGET_DATE_EXPRESSION) String targetDateStr,
            BatchProperties batchProperties) {

        LocalDate targetDate = LocalDate.parse(Objects.requireNonNull(targetDateStr, "Job parameter 'targetDate' is required"));
        Timestamp startOfDay = Timestamp.from(targetDate.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp endOfDay = Timestamp.from(targetDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());

        return new JdbcCursorItemReaderBuilder<MarketDailyAggregationDto>()
                .name("dailyMarketOrderReader")
                .dataSource(dataSource)
                .sql(DAILY_AGGREGATION_SQL)
                .preparedStatementSetter(ps -> {
                    ps.setTimestamp(1, startOfDay);
                    ps.setTimestamp(2, endOfDay);
                })
                .rowMapper(new MarketDailyAggregationRowMapper())
                .fetchSize(batchProperties.chunkSize().dailyMarketSummary())
                .saveState(true)
                .build();
    }

    @Bean
    @StepScope
    public JdbcCursorItemReader<MarketDailyAggregationDto> dailyMarketOrderPartitionReader(
            DataSource dataSource,
            @Value("#{stepExecutionContext['" + RegionPartitioner.CONTEXT_REGION_ID + "']}") Long regionId,
            @Value("#{stepExecutionContext['" + RegionPartitioner.CONTEXT_MIN_TYPE_ID + "']}") Long minTypeId,
            @Value("#{stepExecutionContext['" + RegionPartitioner.CONTEXT_MAX_TYPE_ID + "']}") Long maxTypeId,
            @Value(TARGET_DATE_EXPRESSION) String targetDateStr,
            BatchProperties batchProperties) {

        // Fail fast: a worker must never silently aggregate an unbounded or wrong partition
        long partitionRegionId = Objects.requireNonNull(regionId, "Partition context is missing regionId");
        long partitionMinTypeId = Objects.requireNonNull(minTypeId, "Partition context is missing minTypeId");
        long partitionMaxTypeId = Objects.requireNonNull(maxTypeId, "Partition context is missing maxTypeId");

        LocalDate targetDate = LocalDate.parse(Objects.requireNonNull(targetDateStr, "Job parameter 'targetDate' is required"));
        Timestamp startOfDay = Timestamp.from(targetDate.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp endOfDay = Timestamp.from(targetDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());

        return new JdbcCursorItemReaderBuilder<MarketDailyAggregationDto>()
                .name("dailyMarketOrderPartitionReader")
                .dataSource(dataSource)
                .sql(DAILY_PARTITION_AGGREGATION_SQL)
                .preparedStatementSetter(ps -> {
                    ps.setLong(1, partitionRegionId);
                    ps.setLong(2, partitionMinTypeId);
                    ps.setLong(3, partitionMaxTypeId);
                    ps.setTimestamp(4, startOfDay);
                    ps.setTimestamp(5, endOfDay);
                })
                .rowMapper(new MarketDailyAggregationRowMapper())
                .fetchSize(batchProperties.chunkSize().dailyMarketSummary())
                .saveState(false)
                .build();
    }

    @Bean
    @StepScope
    public RegionPartitioner dailySummaryRegionPartitioner(
            StagingMarketOrderRepository stagingMarketOrderRepository,
            DateDimensionService dateDimensionService,
            @Value(TARGET_DATE_EXPRESSION) String targetDateStr,
            BatchProperties batchProperties) {

        LocalDate targetDate = LocalDate.parse(Objects.requireNonNull(targetDateStr, "Job parameter 'targetDate' is required"));
        Instant startOfDay = targetDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay = targetDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        return new RegionPartitioner(
                () -> stagingMarketOrderRepository.findDistinctRegionIdsByIssuedBetween(startOfDay, endOfDay),
                batchProperties.remotePartitioning().heavyRegionSlices(),
                stagingMarketOrderRepository::findTypeIdQuantileBoundaries,
                () -> dateDimensionService.ensureDateExists(targetDate)
        );
    }

    @Bean(name = STEP_NAME)
    public Step dailyMarketSummaryStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            @Qualifier("dailyMarketOrderReader") JdbcCursorItemReader<MarketDailyAggregationDto> dailyMarketOrderReader,
            DailyMarketSummaryProcessor processor,
            DailyMarketSummaryWriter writer,
            BatchProperties batchProperties) {

        return new StepBuilder(STEP_NAME, jobRepository)
                .<MarketDailyAggregationDto, FactMarketDailySummaryEntity>chunk(batchProperties.chunkSize().dailyMarketSummary(), transactionManager)
                .reader(dailyMarketOrderReader)
                .processor(processor)
                .writer(writer)
                .build();
    }

    @Bean(name = WORKER_STEP_NAME)
    public Step dailyMarketSummaryWorkerStep(
            RemotePartitioningWorkerStepBuilderFactory workerStepBuilderFactory,
            PlatformTransactionManager transactionManager,
            @Qualifier("dailyMarketOrderPartitionReader") JdbcCursorItemReader<MarketDailyAggregationDto> dailyMarketOrderPartitionReader,
            DailyMarketSummaryProcessor processor,
            DailyMarketSummaryWriter writer,
            @Qualifier(BatchAmqpConfig.DAILY_INBOUND_CHANNEL) MessageChannel dailyPartitionRequestsInboundChannel,
            BatchProperties batchProperties,
            JobExplorer jobExplorer) {

        return workerStepBuilderFactory.get(WORKER_STEP_NAME)
                .inputChannel(dailyPartitionRequestsInboundChannel)
                .jobExplorer(jobExplorer)
                .<MarketDailyAggregationDto, FactMarketDailySummaryEntity>chunk(batchProperties.chunkSize().dailyMarketSummary(), transactionManager)
                .reader(dailyMarketOrderPartitionReader)
                .processor(processor)
                .writer(writer)
                .build();
    }

    @Bean(name = MANAGER_STEP_NAME)
    public Step dailyMarketSummaryManagerStep(
            RemotePartitioningManagerStepBuilderFactory managerStepBuilderFactory,
            @Qualifier("dailySummaryRegionPartitioner") RegionPartitioner dailySummaryRegionPartitioner,
            @Qualifier(BatchAmqpConfig.OUTBOUND_CHANNEL) MessageChannel partitionRequestsOutboundChannel,
            JobExplorer jobExplorer,
            BatchProperties batchProperties) {

        return managerStepBuilderFactory.get(MANAGER_STEP_NAME)
                .partitioner(WORKER_STEP_NAME, dailySummaryRegionPartitioner)
                .outputChannel(partitionRequestsOutboundChannel)
                .jobExplorer(jobExplorer)
                .pollInterval(batchProperties.remotePartitioning().pollInterval().toMillis())
                .timeout(batchProperties.remotePartitioning().timeout().toMillis())
                .build();
    }

    @Bean
    public Job dailyMarketSummaryJob(
            JobRepository jobRepository,
            @Qualifier(STEP_NAME) Step dailyMarketSummaryStep,
            @Qualifier(MANAGER_STEP_NAME) Step dailyMarketSummaryManagerStep,
            AdvisoryLockJobExecutionListener advisoryLockListener,
            BatchProperties batchProperties) {

        Step executionStep = batchProperties.remotePartitioning().isPartitioned()
                ? dailyMarketSummaryManagerStep
                : dailyMarketSummaryStep;

        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(dailyTargetDateValidator())
                .start(executionStep)
                .listener(advisoryLockListener)
                .build();
    }
}
