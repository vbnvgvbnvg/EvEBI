package com.github.serbentd.eve.worker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * JPA entity representing an active or historical EVE Online market order snapshot.
 * Maps to the {@code market_orders} database table.
 */
@Entity
@Table(name = "market_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MarketOrderEntity {

    @Id
    @EqualsAndHashCode.Include
    private Long orderId;

    private Long typeId;
    private Long regionId;
    private Long systemId;
    private Long locationId;

    @Column(precision = 18, scale = 2, nullable = false)
    private BigDecimal price;

    private Long volumeRemain;
    private Long volumeTotal;
    private Integer minVolume;
    private Integer duration;
    private Boolean isBuyOrder;

    @Column(length = 32, nullable = false)
    private String orderRange;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private OrderStatus status;

    private Boolean isActive;

    @Column(length = 64)
    private String snapshotId;

    private OffsetDateTime issued;
    private OffsetDateTime polledAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime finishedAt;
}
