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
    private static final long MIN_MARKET_REGION_ID = 10000000L;
    private static final long MAX_MARKET_REGION_ID = 11000000L;

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
                createValidAsyncLauncherProperties(),
                createValidSdeProperties(),
                createValidEsiProperties(),
                createValidScheduleProperties(),
                createValidRemotePartitioningProperties(),
                createValidKubernetesProperties()
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
                createValidAsyncLauncherProperties(),
                new BatchProperties.SdeProperties(
                        "",
                        "   ",
                        "  ",
                        null,
                        null,
                        -50
                ),
                createValidEsiProperties(),
                createValidScheduleProperties(),
                createValidRemotePartitioningProperties(),
                createValidKubernetesProperties()
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
    @DisplayName("Should detect violations when async launcher properties contain non-positive values or nulls")
    void validate_whenAsyncLauncherPropertiesInvalid_shouldDetectViolations() {
        BatchProperties properties = new BatchProperties(
                createValidChunkSizes(),
                new BatchProperties.AsyncLauncherProperties(0, -1, null, null),
                createValidSdeProperties(),
                createValidEsiProperties(),
                createValidScheduleProperties(),
                createValidRemotePartitioningProperties(),
                createValidKubernetesProperties()
        );

        Set<ConstraintViolation<BatchProperties>> violations = validator.validate(properties);
        Set<String> violatedPaths = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedPaths).contains(
                "asyncLauncher.corePoolSize",
                "asyncLauncher.maxPoolSize",
                "asyncLauncher.queueCapacity",
                "asyncLauncher.awaitTermination"
        );
    }

    @Test
    @DisplayName("Should detect violations when ESI properties contain blank values or nulls")
    void validate_whenEsiPropertiesInvalid_shouldDetectViolations() {
        BatchProperties properties = new BatchProperties(
                createValidChunkSizes(),
                createValidAsyncLauncherProperties(),
                createValidSdeProperties(),
                new BatchProperties.EsiProperties(
                        "",
                        "   ",
                        " ",
                        null,
                        null,
                        null,
                        null
                ),
                createValidScheduleProperties(),
                createValidRemotePartitioningProperties(),
                createValidKubernetesProperties()
        );

        Set<ConstraintViolation<BatchProperties>> violations = validator.validate(properties);
        Set<String> violatedPaths = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedPaths).contains(
                "esi.regionsUrl",
                "esi.namesUrl",
                "esi.userAgent",
                "esi.minRegionId",
                "esi.maxRegionId",
                "esi.connectTimeout",
                "esi.readTimeout"
        );
    }

    @Test
    @DisplayName("Should detect violations when schedule properties contain blank crons or nulls")
    void validate_whenSchedulePropertiesInvalid_shouldDetectViolations() {
        BatchProperties properties = new BatchProperties(
                createValidChunkSizes(),
                createValidAsyncLauncherProperties(),
                createValidSdeProperties(),
                createValidEsiProperties(),
                new BatchProperties.ScheduleProperties(
                        null,
                        "",
                        "  ",
                        "   "
                ),
                createValidRemotePartitioningProperties(),
                createValidKubernetesProperties()
        );

        Set<ConstraintViolation<BatchProperties>> violations = validator.validate(properties);
        Set<String> violatedPaths = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedPaths).contains(
                "schedule.enabled",
                "schedule.sdeSyncCron",
                "schedule.dailySummaryCron",
                "schedule.liquidityCron"
        );
    }

    @Test
    @DisplayName("Should detect violations when remote partitioning properties are missing or invalid")
    void validate_whenRemotePartitioningPropertiesInvalid_shouldDetectViolations() {
        BatchProperties properties = new BatchProperties(
                createValidChunkSizes(),
                createValidAsyncLauncherProperties(),
                createValidSdeProperties(),
                createValidEsiProperties(),
                createValidScheduleProperties(),
                new BatchProperties.RemotePartitioningProperties(
                        null,
                        null,
                        "",
                        "   ",
                        "",
                        "",
                        "   ",
                        "",
                        null,
                        null,
                        -1,
                        -5,
                        java.util.Map.of()
                ),
                createValidKubernetesProperties()
        );

        Set<ConstraintViolation<BatchProperties>> violations = validator.validate(properties);
        Set<String> violatedPaths = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedPaths).contains(
                "remotePartitioning.role",
                "remotePartitioning.amqpFlowsEnabled",
                "remotePartitioning.exchange",
                "remotePartitioning.requestsQueue",
                "remotePartitioning.requestsRoutingKey",
                "remotePartitioning.deadLetterExchange",
                "remotePartitioning.deadLetterQueue",
                "remotePartitioning.deadLetterRoutingKey",
                "remotePartitioning.pollInterval",
                "remotePartitioning.timeout",
                "remotePartitioning.workerConcurrency",
                "remotePartitioning.prefetchCount",
                "remotePartitioning.heavyRegionSlices"
        );
    }

    @Test
    @DisplayName("Should derive partitioned status from PartitionRole")
    void isPartitioned_shouldReflectPartitionRole() {
        BatchProperties.RemotePartitioningProperties managerProps = createValidRemotePartitioningProperties();
        assertThat(managerProps.isPartitioned()).isTrue();

        BatchProperties.RemotePartitioningProperties standaloneProps = new BatchProperties.RemotePartitioningProperties(
                PartitionRole.STANDALONE,
                false,
                "eve.batch.partition.exchange",
                "eve.batch.partition.requests",
                "eve.batch.partition.requests",
                "eve.batch.partition.dlx",
                "eve.batch.partition.dlq",
                "eve.batch.partition.dlq",
                Duration.ofSeconds(5),
                Duration.ofMinutes(30),
                4,
                1,
                java.util.Map.of(THE_FORGE_REGION_ID, 4)
        );
        assertThat(standaloneProps.isPartitioned()).isFalse();
    }

    @Test
    @DisplayName("Should detect violations when Kubernetes properties contain blank or null values")
    void validate_whenKubernetesPropertiesInvalid_shouldDetectViolations() {
        BatchProperties properties = new BatchProperties(
                createValidChunkSizes(),
                createValidAsyncLauncherProperties(),
                createValidSdeProperties(),
                createValidEsiProperties(),
                createValidScheduleProperties(),
                createValidRemotePartitioningProperties(),
                new BatchProperties.KubernetesProperties(
                        "",
                        "   ",
                        null,
                        null
                )
        );

        Set<ConstraintViolation<BatchProperties>> violations = validator.validate(properties);
        Set<String> violatedPaths = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(violatedPaths).contains(
                "kubernetes.podName",
                "kubernetes.podNodeName",
                "kubernetes.executionMode",
                "kubernetes.exitOnCompletion"
        );
    }

    @Test
    @DisplayName("Should derive isOneShotJob correctly based on BatchExecutionMode")
    void isOneShotJob_shouldReflectExecutionMode() {
        BatchProperties.KubernetesProperties daemonProps = new BatchProperties.KubernetesProperties(
                "pod-1", "node-1", BatchExecutionMode.DAEMON, true);
        assertThat(daemonProps.isOneShotJob()).isFalse();

        BatchProperties.KubernetesProperties sdeProps = new BatchProperties.KubernetesProperties(
                "pod-1", "node-1", BatchExecutionMode.SDE_SYNC, true);
        assertThat(sdeProps.isOneShotJob()).isTrue();

        BatchProperties.KubernetesProperties dailyProps = new BatchProperties.KubernetesProperties(
                "pod-1", "node-1", BatchExecutionMode.DAILY_MARKET_SUMMARY, true);
        assertThat(dailyProps.isOneShotJob()).isTrue();

        BatchProperties.KubernetesProperties liquidityProps = new BatchProperties.KubernetesProperties(
                "pod-1", "node-1", BatchExecutionMode.ORDER_BOOK_LIQUIDITY, true);
        assertThat(liquidityProps.isOneShotJob()).isTrue();
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
                createValidAsyncLauncherProperties(),
                createValidSdeProperties(),
                createValidEsiProperties(),
                createValidScheduleProperties(),
                createValidRemotePartitioningProperties(),
                createValidKubernetesProperties()
        );
    }

    private static BatchProperties.KubernetesProperties createValidKubernetesProperties() {
        return new BatchProperties.KubernetesProperties(
                "pod-123",
                "node-456",
                BatchExecutionMode.DAEMON,
                false
        );
    }

    private static BatchProperties.ChunkSizeProperties createValidChunkSizes() {
        return new BatchProperties.ChunkSizeProperties(500, 1000);
    }

    private static BatchProperties.AsyncLauncherProperties createValidAsyncLauncherProperties() {
        return new BatchProperties.AsyncLauncherProperties(2, 4, 10, Duration.ofSeconds(60));
    }

    private static BatchProperties.SdeProperties createValidSdeProperties() {
        return new BatchProperties.SdeProperties(
                "https://www.fuzzwork.co.uk/dump/latest/csv/invTypes.csv",
                "https://www.fuzzwork.co.uk/dump/latest/csv/staStations.csv",
                "EveBI-BatchETL/1.0.0 (https://github.com/serbentd/EvEBI)",
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                1000
        );
    }

    private static BatchProperties.EsiProperties createValidEsiProperties() {
        return new BatchProperties.EsiProperties(
                "https://esi.evetech.net/latest/universe/regions/",
                "https://esi.evetech.net/latest/universe/names/",
                "EveBI-BatchETL/1.0.0 (https://github.com/serbentd/EvEBI)",
                MIN_MARKET_REGION_ID,
                MAX_MARKET_REGION_ID,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30)
        );
    }

    private static BatchProperties.ScheduleProperties createValidScheduleProperties() {
        return new BatchProperties.ScheduleProperties(
                true,
                "0 0 3 * * SUN",
                "0 15 0 * * *",
                "0 0/15 * * * *"
        );
    }

    private static BatchProperties.RemotePartitioningProperties createValidRemotePartitioningProperties() {
        return new BatchProperties.RemotePartitioningProperties(
                PartitionRole.MANAGER,
                true,
                "eve.batch.partition.exchange",
                "eve.batch.partition.requests",
                "eve.batch.partition.requests",
                "eve.batch.partition.dlx",
                "eve.batch.partition.dlq",
                "eve.batch.partition.dlq",
                Duration.ofSeconds(5),
                Duration.ofMinutes(30),
                4,
                1,
                java.util.Map.of(THE_FORGE_REGION_ID, 4)
        );
    }
}
