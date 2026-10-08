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
@Table(name = "dim_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class DimItemEntity {

    @Id
    @Column(name = "type_id", nullable = false)
    private Long typeId;

    @Column(name = "type_name", nullable = false, length = 255)
    private String typeName;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "group_name", length = 128)
    private String groupName;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "category_name", length = 128)
    private String categoryName;

    @Column(name = "market_group_id")
    private Long marketGroupId;

    @Column(name = "volume", precision = 18, scale = 4)
    private BigDecimal volume;

    @Column(name = "packaged_volume", precision = 18, scale = 4)
    private BigDecimal packagedVolume;

    @Column(name = "portion_size")
    private Integer portionSize;

    @Column(name = "is_published", nullable = false)
    private Boolean isPublished;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DimItemEntity that)) return false;
        return Objects.equals(typeId, that.typeId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(typeId);
    }
}
