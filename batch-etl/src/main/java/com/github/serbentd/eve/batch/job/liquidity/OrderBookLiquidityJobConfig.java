package com.github.serbentd.eve.batch.job.liquidity;

import com.github.serbentd.eve.batch.config.BatchProperties;
import com.github.serbentd.eve.batch.domain.FactOrderBookLiquidityEntity;
import com.github.serbentd.eve.batch.dto.OrderBookLiquidityAggregationDto;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import com.github.serbentd.eve.batch.reaper.AdvisoryLockJobExecutionListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Spring Batch 5 configuration for {@code orderBookLiquidityJob}.
 * Aggregates active staging market orders into conformed periodic snapshot records
 * in {@code analytics.fact_order_book_liquidity} with spread and volume depth.
 */
@Configuration
public class OrderBookLiquidityJobConfig {

    public static final String JOB_NAME = "orderBookLiquidityJob";
    public static final String STEP_NAME = "orderBookLiquidityStep";

    private static final String LIQUIDITY_AGGREGATION_SQL = """
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
            WHERE is_active = TRUE AND status = 'ACTIVE'
            GROUP BY location_id, type_id
            ORDER BY location_id, type_id
            """;

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
    public Step orderBookLiquidityStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcCursorItemReader<OrderBookLiquidityAggregationDto> orderBookLiquidityReader,
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

    @Bean
    public Job orderBookLiquidityJob(
            JobRepository jobRepository,
            @Qualifier(STEP_NAME) Step orderBookLiquidityStep,
            AdvisoryLockJobExecutionListener advisoryLockListener) {

        return new JobBuilder(JOB_NAME, jobRepository)
                .start(orderBookLiquidityStep)
                .listener(advisoryLockListener)
                .build();
    }
}
