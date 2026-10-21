package com.github.serbentd.eve.batch.job.daily;

import com.github.serbentd.eve.batch.domain.FactMarketDailySummaryEntity;
import com.github.serbentd.eve.batch.dto.MarketDailyAggregationDto;
import com.github.serbentd.eve.batch.service.DateDimensionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Transforms aggregated market order projections into conformed {@link FactMarketDailySummaryEntity} rows,
 * ensuring calendar dimension keys are resolved.
 */
@Component
@StepScope
@Slf4j
public class DailyMarketSummaryProcessor implements ItemProcessor<MarketDailyAggregationDto, FactMarketDailySummaryEntity> {

    private final DateDimensionService dateDimensionService;
    private final LocalDate targetDate;
    private final int dateId;

    public DailyMarketSummaryProcessor(
            DateDimensionService dateDimensionService,
            @Value("#{jobParameters['targetDate'] ?: T(java.time.LocalDate).now().minusDays(1).toString()}") String targetDateStr) {
        this.dateDimensionService = dateDimensionService;
        this.targetDate = LocalDate.parse(targetDateStr);
        this.dateId = DateDimensionService.toDateId(this.targetDate);
    }

    @Override
    public FactMarketDailySummaryEntity process(MarketDailyAggregationDto dto) {
        dateDimensionService.ensureDateExists(targetDate);
        Instant now = Instant.now();

        return FactMarketDailySummaryEntity.builder()
                .dateId(dateId)
                .regionId(dto.regionId())
                .typeId(dto.typeId())
                .openPrice(dto.openPrice())
                .highPrice(dto.highPrice())
                .lowPrice(dto.lowPrice())
                .closePrice(dto.closePrice())
                .volumeTraded(dto.volumeTraded())
                .turnoverIsk(dto.turnoverIsk())
                .orderCount(dto.orderCount())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
