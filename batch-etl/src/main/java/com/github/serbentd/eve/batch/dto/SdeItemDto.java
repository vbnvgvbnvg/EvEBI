package com.github.serbentd.eve.batch.dto;

import java.math.BigDecimal;

/**
 * Immutable transit record representing an EVE item/type extracted from an SDE feed.
 *
 * @param typeId         EVE natural type identifier
 * @param typeName       Official item name
 * @param groupId        Item group identifier
 * @param groupName      Item group name
 * @param categoryId     Top-level item category ID
 * @param categoryName   Top-level item category name
 * @param marketGroupId  In-game market browser tree grouping ID
 * @param volume         Raw cargo volume in m3
 * @param packagedVolume Repackaged transport volume in m3
 * @param portionSize    Batch trade/reprocess unit size
 * @param isPublished    Whether the item is publicly tradeable in-game
 */
public record SdeItemDto(
        Long typeId,
        String typeName,
        Long groupId,
        String groupName,
        Long categoryId,
        String categoryName,
        Long marketGroupId,
        BigDecimal volume,
        BigDecimal packagedVolume,
        Integer portionSize,
        Boolean isPublished
) {}
