package com.github.serbentd.eve.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
@Table(name = "market_orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class StagingMarketOrderEntity {

    @Id
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "type_id", nullable = false)
    private Long typeId;

    @Column(name = "region_id", nullable = false)
    private Long regionId;

    @Column(name = "system_id", nullable = false)
    private Long systemId;

    @Column(name = "location_id", nullable = false)
    private Long locationId;

    @Column(name = "price", nullable = false, precision = 18, scale = 2)
    private BigDecimal price;

    @Column(name = "volume_remain", nullable = false)
    private Long volumeRemain;

    @Column(name = "volume_total", nullable = false)
    private Long volumeTotal;

    @Column(name = "min_volume", nullable = false)
    private Integer minVolume;

    @Column(name = "duration", nullable = false)
    private Integer duration;

    @Column(name = "is_buy_order", nullable = false)
    private Boolean isBuyOrder;

    @Column(name = "order_range", nullable = false, length = 32)
    private String orderRange;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "snapshot_id", length = 64)
    private String snapshotId;

    @Column(name = "issued", nullable = false)
    private Instant issued;

    @Column(name = "polled_at")
    private Instant polledAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StagingMarketOrderEntity that)) return false;
        return Objects.equals(orderId, that.orderId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(orderId);
    }
}
