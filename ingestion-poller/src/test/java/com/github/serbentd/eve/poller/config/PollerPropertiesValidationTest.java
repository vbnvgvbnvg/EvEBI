package com.github.serbentd.eve.poller.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PollerPropertiesValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validate_whenPropertiesValid_shouldHaveZeroViolations() {
        PollerProperties properties = createValidProperties();
        Set<ConstraintViolation<PollerProperties>> violations = validator.validate(properties);
        assertThat(violations).isEmpty();
    }

    @Test
    void validate_whenEsiPropertiesInvalid_shouldDetectConstraintViolations() {
        PollerProperties.EsiProperties invalidEsi = new PollerProperties.EsiProperties(
                "",
                "   ",
                null,
                null,
                null,
                -5
        );
        PollerProperties properties = new PollerProperties(
                invalidEsi,
                createValidSchedulingProperties(),
                createValidAmqpProperties()
        );

        Set<ConstraintViolation<PollerProperties>> violations = validator.validate(properties);
        Set<String> violatedPaths = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedPaths).contains(
                "esi.baseUrl",
                "esi.userAgent",
                "esi.connectTimeout",
                "esi.readTimeout",
                "esi.maxInMemorySize",
                "esi.minErrorRemainThreshold"
        );
    }

    @Test
    void validate_whenSchedulingPropertiesInvalid_shouldDetectConstraintViolations() {
        PollerProperties.SchedulingProperties invalidScheduling = new PollerProperties.SchedulingProperties(
                Collections.emptyList(),
                null,
                null
        );
        PollerProperties properties = new PollerProperties(
                createValidEsiProperties(),
                invalidScheduling,
                createValidAmqpProperties()
        );

        Set<ConstraintViolation<PollerProperties>> violations = validator.validate(properties);
        Set<String> violatedPaths = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedPaths).contains(
                "scheduling.regions",
                "scheduling.initialDelay",
                "scheduling.fixedRate"
        );
    }

    @Test
    void validate_whenAmqpPropertiesBlank_shouldDetectConstraintViolations() {
        PollerProperties.AmqpProperties invalidAmqp = new PollerProperties.AmqpProperties(
                "",
                "   "
        );
        PollerProperties properties = new PollerProperties(
                createValidEsiProperties(),
                createValidSchedulingProperties(),
                invalidAmqp
        );

        Set<ConstraintViolation<PollerProperties>> violations = validator.validate(properties);
        Set<String> violatedPaths = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedPaths).contains(
                "amqp.exchange",
                "amqp.routingKey"
        );
    }

    @Test
    void validate_whenTopLevelRecordsNull_shouldDetectConstraintViolations() {
        PollerProperties properties = new PollerProperties(null, null, null);

        Set<ConstraintViolation<PollerProperties>> violations = validator.validate(properties);
        Set<String> violatedPaths = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedPaths).contains("esi", "scheduling", "amqp");
    }

    private PollerProperties createValidProperties() {
        return new PollerProperties(
                createValidEsiProperties(),
                createValidSchedulingProperties(),
                createValidAmqpProperties()
        );
    }

    private PollerProperties.EsiProperties createValidEsiProperties() {
        return new PollerProperties.EsiProperties(
                "https://esi.evetech.net",
                "EvEBI-Test/1.0",
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                DataSize.ofMegabytes(16),
                20
        );
    }

    private PollerProperties.SchedulingProperties createValidSchedulingProperties() {
        return new PollerProperties.SchedulingProperties(
                List.of(10000002L),
                Duration.ofSeconds(5),
                Duration.ofHours(1)
        );
    }

    private PollerProperties.AmqpProperties createValidAmqpProperties() {
        return new PollerProperties.AmqpProperties(
                "eve.market.orders",
                "eve.market.orders.raw"
        );
    }
}
