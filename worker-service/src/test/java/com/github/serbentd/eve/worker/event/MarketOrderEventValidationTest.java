package com.github.serbentd.eve.worker.event;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MarketOrderEventValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validate_whenEventIsValid_shouldHaveZeroViolations() {
        OffsetDateTime now = OffsetDateTime.now();
        MarketOrderEvent event = new MarketOrderEvent(
                1001L,
                34L,
                10000002L,
                30000142L,
                60003760L,
                BigDecimal.valueOf(15.50),
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

        Set<ConstraintViolation<MarketOrderEvent>> violations = validator.validate(event);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_whenNumericFieldsViolateConstraints_shouldDetectViolations() {
        OffsetDateTime now = OffsetDateTime.now();
        MarketOrderEvent event = new MarketOrderEvent(
                -1L,                        // @Positive violated
                -34L,                       // @Positive violated
                -10000002L,                 // @Positive violated
                -30000142L,                 // @Positive violated
                -60003760L,                 // @Positive violated
                BigDecimal.valueOf(-5.0),   // @Positive violated
                -10L,                       // @PositiveOrZero violated
                0L,                         // @Positive violated
                0,                          // @Positive violated
                -5,                         // @Positive violated
                false,
                "region",
                "snap-123",
                now,
                now
        );

        Set<ConstraintViolation<MarketOrderEvent>> violations = validator.validate(event);

        Set<String> violatedProperties = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedProperties).contains(
                "orderId",
                "typeId",
                "regionId",
                "systemId",
                "locationId",
                "price",
                "volumeRemain",
                "volumeTotal",
                "minVolume",
                "duration"
        );
    }

    @Test
    void validate_whenRequiredFieldsNullOrBlank_shouldDetectViolations() {
        MarketOrderEvent event = new MarketOrderEvent(
                null,       // @NotNull violated
                null,       // @NotNull violated
                null,       // @NotNull violated
                null,       // @NotNull violated
                null,       // @NotNull violated
                null,       // @NotNull violated
                null,       // @NotNull violated
                null,       // @NotNull violated
                null,       // @NotNull violated
                null,       // @NotNull violated
                null,       // @NotNull violated
                "",         // @NotBlank violated
                "   ",      // @NotBlank violated
                null,       // @NotNull violated
                null        // @NotNull violated
        );

        Set<ConstraintViolation<MarketOrderEvent>> violations = validator.validate(event);

        Set<String> violatedProperties = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedProperties).contains(
                "orderId",
                "typeId",
                "regionId",
                "systemId",
                "locationId",
                "price",
                "volumeRemain",
                "volumeTotal",
                "minVolume",
                "duration",
                "isBuyOrder",
                "orderRange",
                "snapshotId",
                "issued",
                "polledAt"
        );
    }
}
