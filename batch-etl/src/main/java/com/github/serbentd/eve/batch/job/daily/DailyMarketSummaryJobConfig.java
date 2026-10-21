package com.github.serbentd.eve.batch.job.daily;

import com.github.serbentd.eve.batch.config.BatchProperties;
import com.github.serbentd.eve.batch.domain.FactMarketDailySummaryEntity;
import com.github.serbentd.eve.batch.dto.MarketDailyAggregationDto;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import com.github.serbentd.eve.batch.reaper.AdvisoryLockJobExecutionListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Spring Batch 5 configuration for {@code dailyMarketSummaryJob}.
 * Aggregates raw staging market orders into conformed periodic daily snapshots
 * using streaming JDBC cursors and atomic SQL batch upserts.
 */
@Configuration
public class DailyMarketSummaryJobConfig {

    public static final String JOB_NAME = "dailyMarketSummaryJob";
    public static final String STEP_NAME = "dailyMarketSummaryStep";

    private static final String DAILY_AGGREGATION_SQL = """
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
                WHERE issued >= ? AND issued < ?
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
            """;

    @Bean
    @StepScope
    public JdbcCursorItemReader<MarketDailyAggregationDto> dailyMarketOrderReader(
            DataSource dataSource,
            @Value("#{jobParameters['targetDate'] ?: T(java.time.LocalDate).now().minusDays(1).toString()}") String targetDateStr,
            BatchProperties batchProperties) {

        LocalDate targetDate = LocalDate.parse(targetDateStr);
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
    public Step dailyMarketSummaryStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcCursorItemReader<MarketDailyAggregationDto> dailyMarketOrderReader,
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

    @Bean
    public Job dailyMarketSummaryJob(
            JobRepository jobRepository,
            @Qualifier(STEP_NAME) Step dailyMarketSummaryStep,
            AdvisoryLockJobExecutionListener advisoryLockListener) {

        return new JobBuilder(JOB_NAME, jobRepository)
                .start(dailyMarketSummaryStep)
                .listener(advisoryLockListener)
                .build();
    }
}
