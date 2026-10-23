package com.github.serbentd.eve.batch.job.sde;

import com.github.serbentd.eve.batch.domain.DimItemEntity;
import com.github.serbentd.eve.batch.domain.DimStationEntity;
import com.github.serbentd.eve.batch.dto.SdeItemDto;
import com.github.serbentd.eve.batch.dto.SdeStationDto;
import com.github.serbentd.eve.batch.service.CitadelDimensionResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SdeProcessorsTest {

    private static final long TRITANIUM_TYPE_ID = 34L;
    private static final long MINERAL_GROUP_ID = 18L;
    private static final long MATERIAL_CATEGORY_ID = 4L;
    private static final long MINERALS_MARKET_GROUP_ID = 1857L;
    private static final long UNKNOWN_TYPE_ID = 99999L;

    private static final long JITA_4_4_STATION_ID = 60003760L;
    private static final long JITA_SYSTEM_ID = 30000142L;
    private static final long THE_FORGE_REGION_ID = 10000002L;

    private static final long TEST_LOW_SEC_STATION_ID = 60001000L;
    private static final long TEST_LOW_SEC_SYSTEM_ID = 30000001L;
    private static final long TEST_NULL_SEC_STATION_ID = 60002000L;
    private static final long TEST_NULL_SEC_SYSTEM_ID = 30000002L;
    private static final long TEST_REGION_ID = 10000001L;
    private static final long TEST_STATION_ID = 1L;
    private static final long TEST_SYSTEM_ID = 1L;

    private final SdeItemProcessor itemProcessor;
    private final SdeStationProcessor stationProcessor;
    private final CitadelDimensionResolver citadelResolver;

    SdeProcessorsTest(@Mock CitadelDimensionResolver citadelResolver) {
        this.citadelResolver = citadelResolver;
        this.itemProcessor = new SdeItemProcessor();
        this.stationProcessor = new SdeStationProcessor(citadelResolver);
    }

    @Test
    @DisplayName("SdeItemProcessor should map valid DTO to DimItemEntity with audit timestamps")
    void itemProcessor_shouldMapValidDto() {
        SdeItemDto dto = new SdeItemDto(
                TRITANIUM_TYPE_ID, "Tritanium", MINERAL_GROUP_ID, "Mineral", MATERIAL_CATEGORY_ID, "Material",
                MINERALS_MARKET_GROUP_ID, new BigDecimal("0.01"), new BigDecimal("0.01"), 1, true
        );

        DimItemEntity entity = itemProcessor.process(dto);

        assertThat(entity).isNotNull();
        assertThat(entity.getTypeId()).isEqualTo(TRITANIUM_TYPE_ID);
        assertThat(entity.getTypeName()).isEqualTo("Tritanium");
        assertThat(entity.getGroupId()).isEqualTo(MINERAL_GROUP_ID);
        assertThat(entity.getGroupName()).isEqualTo("Mineral");
        assertThat(entity.getCategoryId()).isEqualTo(MATERIAL_CATEGORY_ID);
        assertThat(entity.getCategoryName()).isEqualTo("Material");
        assertThat(entity.getVolume()).isEqualByComparingTo("0.01");
        assertThat(entity.getIsPublished()).isTrue();
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("SdeItemProcessor should return null for null input or null typeId")
    void itemProcessor_shouldHandleNulls() {
        assertThat(itemProcessor.process(null)).isNull();
        assertThat(itemProcessor.process(new SdeItemDto(null, "Test", null, null, null, null, null, null, null, null, null))).isNull();
    }

    @Test
    @DisplayName("SdeItemProcessor should provide fallbacks for blank or missing fields")
    void itemProcessor_shouldProvideFallbacks() {
        SdeItemDto dto = new SdeItemDto(
                UNKNOWN_TYPE_ID, "   ", null, null, null, null, null, null, null, null, null
        );

        DimItemEntity entity = itemProcessor.process(dto);

        assertThat(entity).isNotNull();
        assertThat(entity.getTypeName()).isEqualTo("Unknown Type (" + UNKNOWN_TYPE_ID + ")");
        assertThat(entity.getVolume()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(entity.getPackagedVolume()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(entity.getPortionSize()).isEqualTo(1);
        assertThat(entity.getIsPublished()).isTrue();
    }

    @Test
    @DisplayName("SdeStationProcessor should derive security class and structure flag correctly")
    void stationProcessor_shouldDeriveAttributes() {
        when(citadelResolver.isUpwellStructure(JITA_4_4_STATION_ID)).thenReturn(false);

        SdeStationDto npcStation = new SdeStationDto(
                JITA_4_4_STATION_ID, "Jita IV - Moon 4", JITA_SYSTEM_ID, "Jita", THE_FORGE_REGION_ID,
                new BigDecimal("0.95"), null, null
        );

        DimStationEntity entity = stationProcessor.process(npcStation);

        assertThat(entity).isNotNull();
        assertThat(entity.getStationId()).isEqualTo(JITA_4_4_STATION_ID);
        assertThat(entity.getSecurityClass()).isEqualTo("HIGH_SEC");
        assertThat(entity.getIsStructure()).isFalse();

        // Test Low-Sec (0.3)
        DimStationEntity lowSec = stationProcessor.process(new SdeStationDto(
                TEST_LOW_SEC_STATION_ID, "LowSec Stn", TEST_LOW_SEC_SYSTEM_ID, "LowSys", TEST_REGION_ID,
                new BigDecimal("0.30"), null, false
        ));
        assertThat(lowSec.getSecurityClass()).isEqualTo("LOW_SEC");

        // Test Null-Sec (-0.2)
        DimStationEntity nullSec = stationProcessor.process(new SdeStationDto(
                TEST_NULL_SEC_STATION_ID, "NullSec Stn", TEST_NULL_SEC_SYSTEM_ID, "NullSys", TEST_REGION_ID,
                new BigDecimal("-0.20"), null, false
        ));
        assertThat(nullSec.getSecurityClass()).isEqualTo("NULL_SEC");
    }

    @Test
    @DisplayName("SdeStationProcessor should return null for missing stationId or regionId")
    void stationProcessor_shouldHandleNullIds() {
        assertThat(stationProcessor.process(null)).isNull();
        assertThat(stationProcessor.process(new SdeStationDto(
                null, "Test", TEST_SYSTEM_ID, "Sys", TEST_REGION_ID, BigDecimal.ZERO, null, false))).isNull();
        assertThat(stationProcessor.process(new SdeStationDto(
                TEST_STATION_ID, "Test", TEST_SYSTEM_ID, "Sys", null, BigDecimal.ZERO, null, false))).isNull();
    }
}
