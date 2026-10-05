package com.github.serbentd.eve.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
@Table(name = "dim_station")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "region")
public class DimStationEntity {

    @Id
    @Column(name = "station_id", nullable = false)
    private Long stationId;

    @Column(name = "station_name", nullable = false, length = 255)
    private String stationName;

    @Column(name = "solar_system_id", nullable = false)
    private Long solarSystemId;

    @Column(name = "solar_system_name", length = 128)
    private String solarSystemName;

    @Column(name = "region_id", nullable = false)
    private Long regionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", insertable = false, updatable = false)
    private DimRegionEntity region;

    @Column(name = "security_status", precision = 4, scale = 2)
    private BigDecimal securityStatus;

    @Column(name = "security_class", length = 16)
    private String securityClass;

    @Column(name = "is_structure", nullable = false)
    private Boolean isStructure;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DimStationEntity that)) return false;
        return Objects.equals(stationId, that.stationId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(stationId);
    }
}
