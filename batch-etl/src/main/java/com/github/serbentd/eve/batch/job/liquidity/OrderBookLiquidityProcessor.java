package com.github.serbentd.eve.batch.job.liquidity;

import com.github.serbentd.eve.batch.domain.FactOrderBookLiquidityEntity;
import com.github.serbentd.eve.batch.dto.OrderBookLiquidityAggregationDto;
import com.github.serbentd.eve.batch.service.CitadelDimensionResolver;
import com.github.serbentd.eve.batch.service.DateDimensionService;
import com.github.serbentd.eve.batch.service.TimeDimensionService;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * Step-scoped processor transforming order book liquidity aggregations into
 * conformed FactOrderBookLiquidityEntity records with dimension resolution and spread calculation.
 */
@Component
@StepScope
public class OrderBookLiquidityProcessor implements ItemProcessor<OrderBookLiquidityAggregationDto, FactOrderBookLiquidityEntity> {

    private final Instant snapshotInstant;
    private final LocalDate date;
    private final LocalTime time;
    private final int dateId;
    private final int timeId;
    private final DateDimensionService dateDimensionService;
    private final TimeDimensionService timeDimensionService;
    private final CitadelDimensionResolver citadelDimensionResolver;

    public OrderBookLiquidityProcessor(
            @Value("#{jobParameters['snapshotTime'] ?: T(java.time.Instant).now().toString()}") String snapshotTimeStr,
            DateDimensionService dateDimensionService,
            TimeDimensionService timeDimensionService,
            CitadelDimensionResolver citadelDimensionResolver) {

        this.snapshotInstant = Instant.parse(snapshotTimeStr);
        this.date = this.snapshotInstant.atZone(ZoneOffset.UTC).toLocalDate();
        this.time = this.snapshotInstant.atZone(ZoneOffset.UTC).toLocalTime();

        this.dateId = DateDimensionService.toDateId(this.date);
        this.timeId = TimeDimensionService.toTimeId(this.time);
        this.dateDimensionService = dateDimensionService;
        this.timeDimensionService = timeDimensionService;
        this.citadelDimensionResolver = citadelDimensionResolver;
    }

    @Override
    public FactOrderBookLiquidityEntity process(OrderBookLiquidityAggregationDto item) {
        // Ensure temporal dimension rows exist in current transaction
        dateDimensionService.ensureDateExists(date);
        timeDimensionService.ensureTimeExists(time);

        // Resolve station/citadel dimension (handles late-arriving Upwell structures)
        citadelDimensionResolver.resolveOrCreate(item.locationId(), item.systemId(), item.regionId());

        BigDecimal spread = null;
        if (item.bestBid() != null && item.bestAsk() != null) {
            spread = item.bestAsk().subtract(item.bestBid());
        }

        return FactOrderBookLiquidityEntity.builder()
                .dateId(dateId)
                .timeId(timeId)
                .locationId(item.locationId())
                .typeId(item.typeId())
                .bestBid(item.bestBid())
                .bestAsk(item.bestAsk())
                .spread(spread)
                .buyVolume(item.buyVolume())
                .sellVolume(item.sellVolume())
                .buyOrdersCount(item.buyOrdersCount())
                .sellOrdersCount(item.sellOrdersCount())
                .createdAt(snapshotInstant)
                .build();
    }
}
