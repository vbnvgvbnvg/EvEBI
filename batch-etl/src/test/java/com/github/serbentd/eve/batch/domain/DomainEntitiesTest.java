package com.github.serbentd.eve.batch.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class DomainEntitiesTest {

    @Test
    @DisplayName("Should verify DimDateEntity contracts (equals, hashCode, toString)")
    void shouldVerifyDimDateEntity() {
        DimDateEntity date1 = DimDateEntity.builder()
                .dateId(20260101)
                .fullDate(LocalDate.of(2026, 1, 1))
                .yearNum(2026)
                .quarter(1)
                .monthNum(1)
                .monthName("January")
                .weekOfYear(1)
                .dayOfMonth(1)
                .dayOfWeek(4)
                .dayName("Thursday")
                .isWeekend(false)
                .build();

        DimDateEntity date2 = DimDateEntity.builder()
                .dateId(20260101)
                .fullDate(LocalDate.of(2026, 1, 1))
                .build();

        DimDateEntity date3 = DimDateEntity.builder()
                .dateId(20260102)
                .fullDate(LocalDate.of(2026, 1, 2))
                .build();

        assertThat(date1).isEqualTo(date2);
        assertThat(date1).isNotEqualTo(date3);
        assertThat(date1.hashCode()).isEqualTo(date2.hashCode());
        assertThat(date1.toString()).contains("20260101");
        assertThat(new DimDateEntity()).isNotNull();
    }

    @Test
    @DisplayName("Should verify DimTimeEntity contracts (equals, hashCode, toString)")
    void shouldVerifyDimTimeEntity() {
        DimTimeEntity time1 = DimTimeEntity.builder()
                .timeId(1200)
                .timeValue(LocalTime.of(12, 0))
                .hour24(12)
                .hour12(12)
                .minuteOfHour(0)
                .timeFormatted24("12:00")
                .timeFormatted12("12:00 PM")
                .amPm("PM")
                .dayPart("Afternoon")
                .eveTimeBucket("OFF_PEAK")
                .build();

        DimTimeEntity time2 = DimTimeEntity.builder()
                .timeId(1200)
                .build();

        DimTimeEntity time3 = DimTimeEntity.builder()
                .timeId(1201)
                .build();

        assertThat(time1).isEqualTo(time2);
        assertThat(time1).isNotEqualTo(time3);
        assertThat(time1.hashCode()).isEqualTo(time2.hashCode());
        assertThat(time1.toString()).contains("1200");
        assertThat(new DimTimeEntity()).isNotNull();
    }

    @Test
    @DisplayName("Should verify DimRegionEntity contracts (equals, hashCode, toString)")
    void shouldVerifyDimRegionEntity() {
        Instant now = Instant.now();
        DimRegionEntity region1 = DimRegionEntity.builder()
                .regionId(10000002L)
                .regionName("The Forge")
                .description("Caldari trade hub")
                .createdAt(now)
                .updatedAt(now)
                .build();

        DimRegionEntity region2 = DimRegionEntity.builder()
                .regionId(10000002L)
                .build();

        DimRegionEntity region3 = DimRegionEntity.builder()
                .regionId(10000043L)
                .build();

        assertThat(region1).isEqualTo(region2);
        assertThat(region1).isNotEqualTo(region3);
        assertThat(region1.hashCode()).isEqualTo(region2.hashCode());
        assertThat(region1.toString()).contains("The Forge");
        assertThat(new DimRegionEntity()).isNotNull();
    }

    @Test
    @DisplayName("Should verify DimItemEntity contracts (equals, hashCode, toString)")
    void shouldVerifyDimItemEntity() {
        Instant now = Instant.now();
        DimItemEntity item1 = DimItemEntity.builder()
                .typeId(34L)
                .typeName("Tritanium")
                .groupId(18L)
                .groupName("Mineral")
                .categoryId(4L)
                .categoryName("Material")
                .marketGroupId(1857L)
                .volume(new BigDecimal("0.0100"))
                .packagedVolume(new BigDecimal("0.0100"))
                .portionSize(1)
                .isPublished(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        DimItemEntity item2 = DimItemEntity.builder()
                .typeId(34L)
                .build();

        DimItemEntity item3 = DimItemEntity.builder()
                .typeId(35L)
                .build();

        assertThat(item1).isEqualTo(item2);
        assertThat(item1).isNotEqualTo(item3);
        assertThat(item1.hashCode()).isEqualTo(item2.hashCode());
        assertThat(item1.toString()).contains("Tritanium");
        assertThat(new DimItemEntity()).isNotNull();
    }

    @Test
    @DisplayName("Should verify DimStationEntity contracts (equals, hashCode, toString)")
    void shouldVerifyDimStationEntity() {
        Instant now = Instant.now();
        DimStationEntity station1 = DimStationEntity.builder()
                .stationId(60003760L)
                .stationName("Jita IV - Moon 4")
                .solarSystemId(30000142L)
                .solarSystemName("Jita")
                .regionId(10000002L)
                .securityStatus(new BigDecimal("0.95"))
                .securityClass("HIGH_SEC")
                .isStructure(false)
                .createdAt(now)
                .updatedAt(now)
                .build();

        DimStationEntity station2 = DimStationEntity.builder()
                .stationId(60003760L)
                .build();

        DimStationEntity station3 = DimStationEntity.builder()
                .stationId(60008494L)
                .build();

        assertThat(station1).isEqualTo(station2);
        assertThat(station1).isNotEqualTo(station3);
        assertThat(station1.hashCode()).isEqualTo(station2.hashCode());
        assertThat(station1.toString()).contains("Jita IV - Moon 4");
        assertThat(new DimStationEntity()).isNotNull();
    }

    @Test
    @DisplayName("Should verify FactMarketDailySummaryEntity contracts (equals, hashCode, toString)")
    void shouldVerifyFactMarketDailySummaryEntity() {
        Instant now = Instant.now();
        FactMarketDailySummaryEntity fact1 = FactMarketDailySummaryEntity.builder()
                .summaryFactId(1L)
                .dateId(20260101)
                .regionId(10000002L)
                .typeId(34L)
                .openPrice(new BigDecimal("4.1000"))
                .highPrice(new BigDecimal("4.5000"))
                .lowPrice(new BigDecimal("4.0000"))
                .closePrice(new BigDecimal("4.3000"))
                .volumeTraded(100000L)
                .turnoverIsk(new BigDecimal("420000.0000"))
                .orderCount(50)
                .createdAt(now)
                .updatedAt(now)
                .build();

        FactMarketDailySummaryEntity fact2 = FactMarketDailySummaryEntity.builder()
                .summaryFactId(1L)
                .dateId(20260101)
                .regionId(10000002L)
                .typeId(34L)
                .build();

        FactMarketDailySummaryEntity fact3 = FactMarketDailySummaryEntity.builder()
                .summaryFactId(2L)
                .dateId(20260102)
                .regionId(10000002L)
                .typeId(34L)
                .build();

        // Null factId fallback to grain
        FactMarketDailySummaryEntity factNoId1 = FactMarketDailySummaryEntity.builder()
                .dateId(20260101)
                .regionId(10000002L)
                .typeId(34L)
                .build();
        FactMarketDailySummaryEntity factNoId2 = FactMarketDailySummaryEntity.builder()
                .dateId(20260101)
                .regionId(10000002L)
                .typeId(34L)
                .build();

        assertThat(fact1).isEqualTo(fact2);
        assertThat(fact1).isNotEqualTo(fact3);
        assertThat(factNoId1).isEqualTo(factNoId2);
        assertThat(fact1.hashCode()).isEqualTo(fact2.hashCode());
        assertThat(factNoId1.hashCode()).isEqualTo(factNoId2.hashCode());
        assertThat(fact1.toString()).contains("4.1000");
        assertThat(new FactMarketDailySummaryEntity()).isNotNull();
    }

    @Test
    @DisplayName("Should verify FactOrderBookLiquidityEntity contracts (equals, hashCode, toString)")
    void shouldVerifyFactOrderBookLiquidityEntity() {
        Instant now = Instant.now();
        FactOrderBookLiquidityEntity liquidity1 = FactOrderBookLiquidityEntity.builder()
                .liquidityFactId(10L)
                .dateId(20260101)
                .timeId(1100)
                .locationId(60003760L)
                .typeId(34L)
                .bestBid(new BigDecimal("4.2000"))
                .bestAsk(new BigDecimal("4.2500"))
                .spread(new BigDecimal("0.0500"))
                .buyVolume(1000L)
                .sellVolume(2000L)
                .buyOrdersCount(10)
                .sellOrdersCount(20)
                .createdAt(now)
                .build();

        FactOrderBookLiquidityEntity liquidity2 = FactOrderBookLiquidityEntity.builder()
                .liquidityFactId(10L)
                .dateId(20260101)
                .timeId(1100)
                .locationId(60003760L)
                .typeId(34L)
                .build();

        FactOrderBookLiquidityEntity liquidity3 = FactOrderBookLiquidityEntity.builder()
                .liquidityFactId(11L)
                .dateId(20260101)
                .timeId(1200)
                .locationId(60003760L)
                .typeId(34L)
                .build();

        // Null factId fallback
        FactOrderBookLiquidityEntity liqNoId1 = FactOrderBookLiquidityEntity.builder()
                .dateId(20260101)
                .timeId(1100)
                .locationId(60003760L)
                .typeId(34L)
                .build();
        FactOrderBookLiquidityEntity liqNoId2 = FactOrderBookLiquidityEntity.builder()
                .dateId(20260101)
                .timeId(1100)
                .locationId(60003760L)
                .typeId(34L)
                .build();

        assertThat(liquidity1).isEqualTo(liquidity2);
        assertThat(liquidity1).isNotEqualTo(liquidity3);
        assertThat(liqNoId1).isEqualTo(liqNoId2);
        assertThat(liquidity1.hashCode()).isEqualTo(liquidity2.hashCode());
        assertThat(liqNoId1.hashCode()).isEqualTo(liqNoId2.hashCode());
        assertThat(liquidity1.toString()).contains("4.2000");
        assertThat(new FactOrderBookLiquidityEntity()).isNotNull();
    }

    @Test
    @DisplayName("Should verify StagingMarketOrderEntity contracts (equals, hashCode, toString)")
    void shouldVerifyStagingMarketOrderEntity() {
        Instant now = Instant.now();
        StagingMarketOrderEntity order1 = StagingMarketOrderEntity.builder()
                .orderId(12345L)
                .typeId(34L)
                .regionId(10000002L)
                .systemId(30000142L)
                .locationId(60003760L)
                .price(new BigDecimal("4.25"))
                .volumeRemain(100L)
                .volumeTotal(500L)
                .minVolume(1)
                .duration(90)
                .isBuyOrder(false)
                .orderRange("region")
                .status("ACTIVE")
                .isActive(true)
                .snapshotId("snap-001")
                .issued(now)
                .createdAt(now)
                .updatedAt(now)
                .build();

        StagingMarketOrderEntity order2 = StagingMarketOrderEntity.builder()
                .orderId(12345L)
                .build();

        StagingMarketOrderEntity order3 = StagingMarketOrderEntity.builder()
                .orderId(54321L)
                .build();

        assertThat(order1).isEqualTo(order2);
        assertThat(order1).isNotEqualTo(order3);
        assertThat(order1.hashCode()).isEqualTo(order2.hashCode());
        assertThat(order1.toString()).contains("12345");
        assertThat(new StagingMarketOrderEntity()).isNotNull();
    }
}
