package com.github.serbentd.eve.batch.config;

import com.github.serbentd.eve.batch.dto.SdeItemDto;
import com.github.serbentd.eve.batch.dto.SdeStationDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class BatchPropertiesValidationTest {

    private static final long TRITANIUM_TYPE_ID = 34L;
    private static final long MINERAL_GROUP_ID = 18L;
    private static final long MATERIAL_CATEGORY_ID = 4L;
    private static final long MINERALS_MARKET_GROUP_ID = 1857L;
    private static final long JITA_4_4_STATION_ID = 60003760L;
    private static final long JITA_SYSTEM_ID = 30000142L;
    private static final long THE_FORGE_REGION_ID = 10000002L;

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    @DisplayName("Should have zero violations when all properties are valid")
    void validate_whenPropertiesValid_shouldHaveZeroViolations() {
        BatchProperties properties = createValidProperties();
        Set<ConstraintViolation<BatchProperties>> violations = validator.validate(properties);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Should detect violations when chunk sizes are non-positive or null")
    void validate_whenChunkSizeInvalid_shouldDetectViolations() {
        BatchProperties properties = new BatchProperties(
                new BatchProperties.ChunkSizeProperties(-1, 0),
                createValidSdeProperties()
        );

        Set<ConstraintViolation<BatchProperties>> violations = validator.validate(properties);
        Set<String> violatedPaths = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedPaths).contains(
                "chunkSize.dailyMarketSummary",
                "chunkSize.orderBookLiquidity"
        );
    }

    @Test
    @DisplayName("Should detect violations when SDE properties contain blank URLs or invalid timeouts")
    void validate_whenSdePropertiesInvalid_shouldDetectViolations() {
        BatchProperties properties = new BatchProperties(
                createValidChunkSizes(),
                new BatchProperties.SdeProperties(
                        "",
                        "   ",
                        "  ",
                        null,
                        null,
                        -50
                )
        );

        Set<ConstraintViolation<BatchProperties>> violations = validator.validate(properties);
        Set<String> violatedPaths = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedPaths).contains(
                "sde.itemsUrl",
                "sde.stationsUrl",
                "sde.userAgent",
                "sde.connectTimeout",
                "sde.readTimeout",
                "sde.chunkSize"
        );
    }

    @Test
    @DisplayName("Should verify SdeItemDto and SdeStationDto record contracts")
    void shouldVerifyDtoContracts() {
        SdeItemDto item1 = new SdeItemDto(
                TRITANIUM_TYPE_ID, "Tritanium", MINERAL_GROUP_ID, "Mineral", MATERIAL_CATEGORY_ID, "Material",
                MINERALS_MARKET_GROUP_ID, new BigDecimal("0.01"), new BigDecimal("0.01"), 1, true
        );
        SdeItemDto item2 = new SdeItemDto(
                TRITANIUM_TYPE_ID, "Tritanium", MINERAL_GROUP_ID, "Mineral", MATERIAL_CATEGORY_ID, "Material",
                MINERALS_MARKET_GROUP_ID, new BigDecimal("0.01"), new BigDecimal("0.01"), 1, true
        );

        assertThat(item1).isEqualTo(item2);
        assertThat(item1.hashCode()).isEqualTo(item2.hashCode());
        assertThat(item1.typeName()).isEqualTo("Tritanium");

        SdeStationDto station1 = new SdeStationDto(
                JITA_4_4_STATION_ID, "Jita IV - Moon 4", JITA_SYSTEM_ID, "Jita",
                THE_FORGE_REGION_ID, new BigDecimal("0.95"), "HIGH_SEC", false
        );
        SdeStationDto station2 = new SdeStationDto(
                JITA_4_4_STATION_ID, "Jita IV - Moon 4", JITA_SYSTEM_ID, "Jita",
                THE_FORGE_REGION_ID, new BigDecimal("0.95"), "HIGH_SEC", false
        );

        assertThat(station1).isEqualTo(station2);
        assertThat(station1.hashCode()).isEqualTo(station2.hashCode());
        assertThat(station1.stationName()).isEqualTo("Jita IV - Moon 4");
    }

    private static BatchProperties createValidProperties() {
        return new BatchProperties(
                createValidChunkSizes(),
                createValidSdeProperties()
        );
    }

    private static BatchProperties.ChunkSizeProperties createValidChunkSizes() {
        return new BatchProperties.ChunkSizeProperties(500, 1000);
    }

    private static BatchProperties.SdeProperties createValidSdeProperties() {
        return new BatchProperties.SdeProperties(
                "https://www.fuzzwork.co.uk/dump/latest/invTypes.csv",
                "https://www.fuzzwork.co.uk/dump/latest/staStations.csv",
                "EveBI-BatchETL/1.0.0 (https://github.com/serbentd/EvEBI)",
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                1000
        );
    }
}
