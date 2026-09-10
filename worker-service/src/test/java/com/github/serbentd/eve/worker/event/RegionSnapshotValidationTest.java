package com.github.serbentd.eve.worker.event;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RegionSnapshotValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validate_whenEventIsValid_shouldHaveZeroViolations() {
        RegionSnapshotCompletedEvent event = new RegionSnapshotCompletedEvent(
                10000002L,
                "snap-123",
                450,
                5,
                OffsetDateTime.now()
        );

        Set<ConstraintViolation<RegionSnapshotCompletedEvent>> violations = validator.validate(event);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_whenTotalOrdersIsZero_shouldBeValid() {
        RegionSnapshotCompletedEvent event = new RegionSnapshotCompletedEvent(
                10000002L,
                "snap-empty",
                0,
                1,
                OffsetDateTime.now()
        );

        Set<ConstraintViolation<RegionSnapshotCompletedEvent>> violations = validator.validate(event);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_whenNumericFieldsViolateConstraints_shouldDetectViolations() {
        RegionSnapshotCompletedEvent event = new RegionSnapshotCompletedEvent(
                -1L,            // @Positive violated
                "snap-123",
                -10,            // @PositiveOrZero violated
                0,              // @Positive violated (totalPages must be >= 1)
                OffsetDateTime.now()
        );

        Set<ConstraintViolation<RegionSnapshotCompletedEvent>> violations = validator.validate(event);

        Set<String> violatedProperties = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedProperties).contains("regionId", "totalOrders", "totalPages");
    }

    @Test
    void validate_whenRequiredFieldsNullOrBlank_shouldDetectViolations() {
        RegionSnapshotCompletedEvent event = new RegionSnapshotCompletedEvent(
                null,           // @NotNull violated
                "   ",          // @NotBlank violated
                null,           // @NotNull violated
                null,           // @NotNull violated
                null            // @NotNull violated
        );

        Set<ConstraintViolation<RegionSnapshotCompletedEvent>> violations = validator.validate(event);

        Set<String> violatedProperties = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedProperties).contains(
                "regionId",
                "snapshotId",
                "totalOrders",
                "totalPages",
                "completedAt"
        );
    }
}
