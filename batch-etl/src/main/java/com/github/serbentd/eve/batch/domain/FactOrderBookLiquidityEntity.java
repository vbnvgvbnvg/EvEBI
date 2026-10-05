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
@Table(name = "fact_order_book_liquidity")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"date", "time", "station", "item"})
public class FactOrderBookLiquidityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "liquidity_fact_id", nullable = false)
    private Long liquidityFactId;

    @Column(name = "date_id", nullable = false)
    private Integer dateId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "date_id", insertable = false, updatable = false)
    private DimDateEntity date;

    @Column(name = "time_id", nullable = false)
    private Integer timeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "time_id", insertable = false, updatable = false)
    private DimTimeEntity time;

    @Column(name = "location_id", nullable = false)
    private Long locationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", insertable = false, updatable = false)
    private DimStationEntity station;

    @Column(name = "type_id", nullable = false)
    private Long typeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "type_id", insertable = false, updatable = false)
    private DimItemEntity item;

    @Column(name = "best_bid", precision = 18, scale = 4)
    private BigDecimal bestBid;

    @Column(name = "best_ask", precision = 18, scale = 4)
    private BigDecimal bestAsk;

    @Column(name = "spread", precision = 18, scale = 4)
    private BigDecimal spread;

    @Column(name = "buy_volume", nullable = false)
    private Long buyVolume;

    @Column(name = "sell_volume", nullable = false)
    private Long sellVolume;

    @Column(name = "buy_orders_count", nullable = false)
    private Integer buyOrdersCount;

    @Column(name = "sell_orders_count", nullable = false)
    private Integer sellOrdersCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FactOrderBookLiquidityEntity that)) return false;
        if (liquidityFactId != null && that.liquidityFactId != null) {
            return Objects.equals(liquidityFactId, that.liquidityFactId);
        }
        return Objects.equals(dateId, that.dateId)
                && Objects.equals(timeId, that.timeId)
                && Objects.equals(locationId, that.locationId)
                && Objects.equals(typeId, that.typeId);
    }

    @Override
    public int hashCode() {
        if (liquidityFactId != null) {
            return Objects.hashCode(liquidityFactId);
        }
        return Objects.hash(dateId, timeId, locationId, typeId);
    }
}
