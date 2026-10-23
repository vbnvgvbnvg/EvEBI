package com.github.serbentd.eve.batch.job.sde;

import com.github.serbentd.eve.batch.domain.DimItemEntity;
import com.github.serbentd.eve.batch.dto.SdeItemDto;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Item processor that maps raw transit records (SdeItemDto) into conformed domain entities (DimItemEntity),
 * applying default fallbacks, validation, and audit timestamps.
 */
@Component
public class SdeItemProcessor implements ItemProcessor<SdeItemDto, DimItemEntity> {

    @Override
    public DimItemEntity process(SdeItemDto item) {
        if (item == null || item.typeId() == null) {
            return null;
        }

        String typeName = item.typeName() != null && !item.typeName().isBlank()
                ? item.typeName().trim()
                : "Unknown Type (" + item.typeId() + ")";

        BigDecimal volume = item.volume() != null ? item.volume() : BigDecimal.ZERO;
        BigDecimal packagedVolume = item.packagedVolume() != null ? item.packagedVolume() : volume;
        Integer portionSize = item.portionSize() != null ? item.portionSize() : 1;
        Boolean isPublished = item.isPublished() != null ? item.isPublished() : true;

        Instant now = Instant.now();
        return DimItemEntity.builder()
                .typeId(item.typeId())
                .typeName(typeName)
                .groupId(item.groupId())
                .groupName(item.groupName())
                .categoryId(item.categoryId())
                .categoryName(item.categoryName())
                .marketGroupId(item.marketGroupId())
                .volume(volume)
                .packagedVolume(packagedVolume)
                .portionSize(portionSize)
                .isPublished(isPublished)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
