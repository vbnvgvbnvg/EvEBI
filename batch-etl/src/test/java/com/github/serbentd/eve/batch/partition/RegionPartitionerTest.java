package com.github.serbentd.eve.batch.partition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionPartitionerTest {

    private static final long THE_FORGE_REGION_ID = 10000002L;
    private static final long DOMAIN_REGION_ID = 10000043L;
    private static final long SINQ_LAISON_REGION_ID = 10000032L;

    @Test
    @DisplayName("Should return empty partitions when region supplier returns empty list")
    void partition_whenNoRegions_shouldReturnEmptyMap() {
        AtomicBoolean preSeeded = new AtomicBoolean(false);
        RegionPartitioner partitioner = new RegionPartitioner(
                Collections::emptyList,
                Map.of(THE_FORGE_REGION_ID, 4),
                (id, slices) -> List.of(34L, 1200L, 18000L, 350000L),
                () -> preSeeded.set(true)
        );

        Map<String, ExecutionContext> partitions = partitioner.partition(10);

        assertThat(partitions).isEmpty();
        assertThat(preSeeded).isTrue();
    }

    @Test
    @DisplayName("Should create standard partitions for normal regions")
    void partition_whenStandardRegions_shouldCreateFullRangePartitions() {
        RegionPartitioner partitioner = new RegionPartitioner(
                () -> List.of(DOMAIN_REGION_ID, SINQ_LAISON_REGION_ID),
                Map.of(THE_FORGE_REGION_ID, 4),
                (id, slices) -> List.of(34L, 1200L, 18000L, 350000L),
                () -> {}
        );

        Map<String, ExecutionContext> partitions = partitioner.partition(10);

        assertThat(partitions).hasSize(2);

        ExecutionContext domainContext = partitions.get("partition_region_" + DOMAIN_REGION_ID);
        assertThat(domainContext).isNotNull();
        assertThat(domainContext.getLong(RegionPartitioner.CONTEXT_REGION_ID)).isEqualTo(DOMAIN_REGION_ID);
        assertThat(domainContext.getLong(RegionPartitioner.CONTEXT_MIN_TYPE_ID)).isEqualTo(0L);
        assertThat(domainContext.getLong(RegionPartitioner.CONTEXT_MAX_TYPE_ID)).isEqualTo(Long.MAX_VALUE);

        ExecutionContext sinqContext = partitions.get("partition_region_" + SINQ_LAISON_REGION_ID);
        assertThat(sinqContext).isNotNull();
        assertThat(sinqContext.getLong(RegionPartitioner.CONTEXT_REGION_ID)).isEqualTo(SINQ_LAISON_REGION_ID);
        assertThat(sinqContext.getLong(RegionPartitioner.CONTEXT_MIN_TYPE_ID)).isEqualTo(0L);
        assertThat(sinqContext.getLong(RegionPartitioner.CONTEXT_MAX_TYPE_ID)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("Should create non-overlapping quantile type range slices across multiple configured heavy regions")
    void partition_whenMultipleHeavyRegionsPresent_shouldSubPartitionEachIntoQuantileSlices() {
        // The Forge: 4 slices, Domain: 2 slices, Sinq Laison: standard (1 slice)
        Map<Long, Integer> heavyRegions = Map.of(
                THE_FORGE_REGION_ID, 4,
                DOMAIN_REGION_ID, 2
        );

        RegionPartitioner partitioner = new RegionPartitioner(
                () -> List.of(THE_FORGE_REGION_ID, DOMAIN_REGION_ID, SINQ_LAISON_REGION_ID),
                heavyRegions,
                (regionId, slices) -> regionId == THE_FORGE_REGION_ID
                        ? List.of(34L, 1200L, 18000L, 350000L)
                        : List.of(34L, 29668L),
                () -> {}
        );

        Map<String, ExecutionContext> partitions = partitioner.partition(10);

        // 4 quantile slices for The Forge + 2 quantile slices for Domain + 1 standard for Sinq Laison = 7 partitions
        assertThat(partitions).hasSize(7);

        // The Forge Slice 0: [0, 1200)
        ExecutionContext slice0 = partitions.get("partition_region_" + THE_FORGE_REGION_ID + "_slice_0");
        assertThat(slice0).isNotNull();
        assertThat(slice0.getLong(RegionPartitioner.CONTEXT_REGION_ID)).isEqualTo(THE_FORGE_REGION_ID);
        assertThat(slice0.getLong(RegionPartitioner.CONTEXT_MIN_TYPE_ID)).isEqualTo(0L);
        assertThat(slice0.getLong(RegionPartitioner.CONTEXT_MAX_TYPE_ID)).isEqualTo(1200L);

        // The Forge Slice 1: [1200, 18000)
        ExecutionContext slice1 = partitions.get("partition_region_" + THE_FORGE_REGION_ID + "_slice_1");
        assertThat(slice1).isNotNull();
        assertThat(slice1.getLong(RegionPartitioner.CONTEXT_MIN_TYPE_ID)).isEqualTo(1200L);
        assertThat(slice1.getLong(RegionPartitioner.CONTEXT_MAX_TYPE_ID)).isEqualTo(18000L);

        // The Forge Slice 2: [18000, 350000)
        ExecutionContext slice2 = partitions.get("partition_region_" + THE_FORGE_REGION_ID + "_slice_2");
        assertThat(slice2).isNotNull();
        assertThat(slice2.getLong(RegionPartitioner.CONTEXT_MIN_TYPE_ID)).isEqualTo(18000L);
        assertThat(slice2.getLong(RegionPartitioner.CONTEXT_MAX_TYPE_ID)).isEqualTo(350000L);

        // The Forge Slice 3: [350000, Long.MAX_VALUE)
        ExecutionContext slice3 = partitions.get("partition_region_" + THE_FORGE_REGION_ID + "_slice_3");
        assertThat(slice3).isNotNull();
        assertThat(slice3.getLong(RegionPartitioner.CONTEXT_MIN_TYPE_ID)).isEqualTo(350000L);
        assertThat(slice3.getLong(RegionPartitioner.CONTEXT_MAX_TYPE_ID)).isEqualTo(Long.MAX_VALUE);

        // Domain Slice 0: [0, 29668)
        ExecutionContext domainSlice0 = partitions.get("partition_region_" + DOMAIN_REGION_ID + "_slice_0");
        assertThat(domainSlice0).isNotNull();
        assertThat(domainSlice0.getLong(RegionPartitioner.CONTEXT_REGION_ID)).isEqualTo(DOMAIN_REGION_ID);
        assertThat(domainSlice0.getLong(RegionPartitioner.CONTEXT_MIN_TYPE_ID)).isEqualTo(0L);
        assertThat(domainSlice0.getLong(RegionPartitioner.CONTEXT_MAX_TYPE_ID)).isEqualTo(29668L);

        // Domain Slice 1: [29668, Long.MAX_VALUE)
        ExecutionContext domainSlice1 = partitions.get("partition_region_" + DOMAIN_REGION_ID + "_slice_1");
        assertThat(domainSlice1).isNotNull();
        assertThat(domainSlice1.getLong(RegionPartitioner.CONTEXT_MIN_TYPE_ID)).isEqualTo(29668L);
        assertThat(domainSlice1.getLong(RegionPartitioner.CONTEXT_MAX_TYPE_ID)).isEqualTo(Long.MAX_VALUE);

        // Sinq Laison: single partition
        ExecutionContext sinq = partitions.get("partition_region_" + SINQ_LAISON_REGION_ID);
        assertThat(sinq).isNotNull();
        assertThat(sinq.getLong(RegionPartitioner.CONTEXT_MIN_TYPE_ID)).isEqualTo(0L);
        assertThat(sinq.getLong(RegionPartitioner.CONTEXT_MAX_TYPE_ID)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("Should fall back to a single partition when heavy region has fewer than 2 quantile buckets")
    void partition_whenHeavyRegionHasSingleQuantileBucket_shouldCreateSinglePartition() {
        RegionPartitioner partitioner = new RegionPartitioner(
                () -> List.of(THE_FORGE_REGION_ID),
                Map.of(THE_FORGE_REGION_ID, 4),
                (id, slices) -> List.of(34L),
                () -> {}
        );

        Map<String, ExecutionContext> partitions = partitioner.partition(10);

        assertThat(partitions).containsOnlyKeys("partition_region_" + THE_FORGE_REGION_ID);
        ExecutionContext context = partitions.get("partition_region_" + THE_FORGE_REGION_ID);
        assertThat(context.getLong(RegionPartitioner.CONTEXT_MIN_TYPE_ID)).isEqualTo(0L);
        assertThat(context.getLong(RegionPartitioner.CONTEXT_MAX_TYPE_ID)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("Should reject non-positive heavy region slice counts")
    void constructor_whenSlicesNotPositive_shouldThrow() {
        assertThatThrownBy(() -> new RegionPartitioner(
                Collections::emptyList, Map.of(THE_FORGE_REGION_ID, 0), (id, s) -> List.of(), () -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
