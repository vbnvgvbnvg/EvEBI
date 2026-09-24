package com.github.serbentd.eve.poller.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EventSerializationTest {

    private static final Long THE_FORGE_REGION_ID = 10000002L;
    private static final Long JITA_SYSTEM_ID = 30000142L;
    private static final Long JITA_4_4_STATION_ID = 60003760L;

    private static ObjectMapper objectMapper;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void marketOrderEvent_serializationAndDeserialization_shouldPreserveAllFields() throws Exception {
        OffsetDateTime testTime = OffsetDateTime.of(2026, 9, 18, 12, 0, 0, 0, ZoneOffset.UTC);
        MarketOrderEvent original = new MarketOrderEvent(
                1001L,
                34L,
                THE_FORGE_REGION_ID,
                JITA_SYSTEM_ID,
                JITA_4_4_STATION_ID,
                new BigDecimal("15.50"),
                50L,
                100L,
                1,
                90,
                false,
                "region",
                "snap-123",
                testTime,
                testTime
        );

        String json = objectMapper.writeValueAsString(original);

        // Verify key JSON attributes and ISO-8601 string formatting
        assertThat(json).contains("\"orderId\":1001");
        assertThat(json).contains("\"price\":15.50");
        assertThat(json).contains("\"issued\":\"2026-09-18T12:00:00Z\"");
        assertThat(json).contains("\"snapshotId\":\"snap-123\"");

        MarketOrderEvent deserialized = objectMapper.readValue(json, MarketOrderEvent.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void regionSnapshotCompletedEvent_serializationAndDeserialization_shouldPreserveAllFields() throws Exception {
        OffsetDateTime testTime = OffsetDateTime.of(2026, 9, 18, 13, 0, 0, 0, ZoneOffset.UTC);
        RegionSnapshotCompletedEvent original = new RegionSnapshotCompletedEvent(
                THE_FORGE_REGION_ID,
                "snap-456",
                1250,
                15,
                testTime
        );

        String json = objectMapper.writeValueAsString(original);

        assertThat(json).contains("\"regionId\":10000002");
        assertThat(json).contains("\"snapshotId\":\"snap-456\"");
        assertThat(json).contains("\"totalOrders\":1250");
        assertThat(json).contains("\"totalPages\":15");
        assertThat(json).contains("\"completedAt\":\"2026-09-18T13:00:00Z\"");

        RegionSnapshotCompletedEvent deserialized = objectMapper.readValue(json, RegionSnapshotCompletedEvent.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void marketOrderEvent_validation_whenValidAndInvalid_shouldEnforceConstraints() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        MarketOrderEvent valid = new MarketOrderEvent(
                1001L,
                34L,
                THE_FORGE_REGION_ID,
                JITA_SYSTEM_ID,
                JITA_4_4_STATION_ID,
                new BigDecimal("15.50"),
                50L,
                100L,
                1,
                90,
                false,
                "region",
                "snap-123",
                now,
                now
        );

        Set<ConstraintViolation<MarketOrderEvent>> validViolations = validator.validate(valid);
        assertThat(validViolations).isEmpty();

        MarketOrderEvent invalid = new MarketOrderEvent(
                -1L,
                -34L,
                -10000002L,
                -30000142L,
                -60003760L,
                new BigDecimal("-5.0"),
                -10L,
                0L,
                0,
                -5,
                null,
                "",
                "   ",
                null,
                null
        );

        Set<ConstraintViolation<MarketOrderEvent>> invalidViolations = validator.validate(invalid);
        Set<String> violatedPaths = invalidViolations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedPaths).contains(
                "orderId", "typeId", "regionId", "systemId", "locationId",
                "price", "volumeRemain", "volumeTotal", "minVolume", "duration",
                "isBuyOrder", "orderRange", "snapshotId", "issued", "polledAt"
        );
    }

    @Test
    void regionSnapshotCompletedEvent_validation_whenValidAndInvalid_shouldEnforceConstraints() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RegionSnapshotCompletedEvent valid = new RegionSnapshotCompletedEvent(
                THE_FORGE_REGION_ID,
                "snap-123",
                500,
                5,
                now
        );

        Set<ConstraintViolation<RegionSnapshotCompletedEvent>> validViolations = validator.validate(valid);
        assertThat(validViolations).isEmpty();

        RegionSnapshotCompletedEvent invalid = new RegionSnapshotCompletedEvent(
                -1L,
                "",
                -1,
                0,
                null
        );

        Set<ConstraintViolation<RegionSnapshotCompletedEvent>> invalidViolations = validator.validate(invalid);
        Set<String> violatedPaths = invalidViolations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedPaths).contains(
                "regionId", "snapshotId", "totalOrders", "totalPages", "completedAt"
        );
    }
}
