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

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "dim_region")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class DimRegionEntity {

    @Id
    @Column(name = "region_id", nullable = false)
    private Long regionId;

    @Column(name = "region_name", nullable = false, length = 128)
    private String regionName;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DimRegionEntity that)) return false;
        return Objects.equals(regionId, that.regionId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(regionId);
    }
}
