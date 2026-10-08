package com.github.serbentd.eve.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "fact_market_daily_summary")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"date", "region", "item"})
public class FactMarketDailySummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "summary_fact_id", nullable = false)
    private Long summaryFactId;

    @Column(name = "date_id", nullable = false)
    private Integer dateId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "date_id", insertable = false, updatable = false)
    private DimDateEntity date;

    @Column(name = "region_id", nullable = false)
    private Long regionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", insertable = false, updatable = false)
    private DimRegionEntity region;

    @Column(name = "type_id", nullable = false)
    private Long typeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "type_id", insertable = false, updatable = false)
    private DimItemEntity item;

    @Column(name = "open_price", precision = 18, scale = 4, nullable = false)
    private BigDecimal openPrice;

    @Column(name = "high_price", precision = 18, scale = 4, nullable = false)
    private BigDecimal highPrice;

    @Column(name = "low_price", precision = 18, scale = 4, nullable = false)
    private BigDecimal lowPrice;

    @Column(name = "close_price", precision = 18, scale = 4, nullable = false)
    private BigDecimal closePrice;

    @Column(name = "volume_traded", nullable = false)
    private Long volumeTraded;

    @Column(name = "turnover_isk", precision = 24, scale = 4, nullable = false)
    private BigDecimal turnoverIsk;

    @Column(name = "order_count", nullable = false)
    private Integer orderCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FactMarketDailySummaryEntity that)) return false;
        if (summaryFactId != null && that.summaryFactId != null) {
            return Objects.equals(summaryFactId, that.summaryFactId);
        }
        return Objects.equals(dateId, that.dateId)
                && Objects.equals(regionId, that.regionId)
                && Objects.equals(typeId, that.typeId);
    }

    @Override
    public int hashCode() {
        if (summaryFactId != null) {
            return Objects.hashCode(summaryFactId);
        }
        return Objects.hash(dateId, regionId, typeId);
    }
}
