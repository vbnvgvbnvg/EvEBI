package com.github.serbentd.eve.common.event;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class CommonEventValidationTest {

    private static final long SAMPLE_ORDER_ID = 1001L;
    private static final long TRITANIUM_TYPE_ID = 34L;
    private static final long THE_FORGE_REGION_ID = 10000002L;
    private static final long JITA_SYSTEM_ID = 30000142L;
    private static final long JITA_4_4_STATION_ID = 60003760L;

    @Test
    @DisplayName("Shared MarketOrderEvent and RegionSnapshotCompletedEvent should validate cleanly")
    void sharedEvents_shouldPassValidationWhenValid() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        MarketOrderEvent orderEvent = new MarketOrderEvent(
                SAMPLE_ORDER_ID, TRITANIUM_TYPE_ID, THE_FORGE_REGION_ID, JITA_SYSTEM_ID, JITA_4_4_STATION_ID,
                new BigDecimal("5.25"), 100L, 500L, 1, 90, false, "region", "snap-1", now, now
        );
        RegionSnapshotCompletedEvent snapshotEvent = new RegionSnapshotCompletedEvent(
                THE_FORGE_REGION_ID, "snap-1", 100, 1, now
        );

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(orderEvent)).isEmpty();
            assertThat(validator.validate(snapshotEvent)).isEmpty();
        }
    }
}
