package com.github.serbentd.eve.batch.partition;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Spring Batch {@link Partitioner} that splits analytical steps into discrete,
 * non-overlapping partitions by EVE Online market region ID, with quantile-based
 * item range sub-partitioning for configured high-volume trade hubs (e.g. The Forge, Domain).
 * <p>
 * Runs exclusively on the manager node. Before partitions are generated, the
 * {@code preSeeder} callback registers shared dimension rows once to prevent
 * concurrent worker insertion races.
 */
@Slf4j
public class RegionPartitioner implements Partitioner {

    public static final String CONTEXT_REGION_ID = "regionId";
    public static final String CONTEXT_MIN_TYPE_ID = "minTypeId";
    public static final String CONTEXT_MAX_TYPE_ID = "maxTypeId";
    public static final String CONTEXT_PARTITION_NAME = "partitionName";

    private final Supplier<List<Long>> regionIdSupplier;
    private final Map<Long, Integer> heavyRegionSlices;
    private final BiFunction<Long, Integer, List<Long>> quantileBoundaryLookup;
    private final Runnable preSeeder;

    public RegionPartitioner(
            Supplier<List<Long>> regionIdSupplier,
            Map<Long, Integer> heavyRegionSlices,
            BiFunction<Long, Integer, List<Long>> quantileBoundaryLookup,
            Runnable preSeeder) {

        this.regionIdSupplier = Objects.requireNonNull(regionIdSupplier, "regionIdSupplier");
        this.heavyRegionSlices = validateAndCopySlices(heavyRegionSlices);
        this.quantileBoundaryLookup = Objects.requireNonNull(quantileBoundaryLookup, "quantileBoundaryLookup");
        this.preSeeder = Objects.requireNonNull(preSeeder, "preSeeder");
    }

    private static Map<Long, Integer> validateAndCopySlices(Map<Long, Integer> slices) {
        Objects.requireNonNull(slices, "heavyRegionSlices");
        for (Map.Entry<Long, Integer> entry : slices.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 1) {
                throw new IllegalArgumentException(
                        "Invalid heavyRegionSlices entry (" + entry.getKey() + " -> " + entry.getValue()
                                + "): regionId must be non-null and slice count must be positive");
            }
        }
        return Map.copyOf(slices);
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        preSeeder.run();

        List<Long> regionIds = regionIdSupplier.get();
        if (regionIds == null || regionIds.isEmpty()) {
            log.info("No active market regions identified for partitioning. Returning empty partition map.");
            return Collections.emptyMap();
        }

        log.info("Partitioning across {} active market regions (configuredHeavyRegions={})",
                regionIds.size(), heavyRegionSlices);

        Map<String, ExecutionContext> partitions = new HashMap<>();

        for (Long regionId : regionIds) {
            int targetSlices = heavyRegionSlices.getOrDefault(regionId, 1);
            if (targetSlices > 1) {
                createHeavyRegionSlices(partitions, regionId, targetSlices);
            } else {
                createStandardRegionPartition(partitions, regionId);
            }
        }

        log.info("Generated {} total partition execution contexts", partitions.size());
        return partitions;
    }

    private void createStandardRegionPartition(Map<String, ExecutionContext> partitions, long regionId) {
        String partitionKey = "partition_region_" + regionId;
        partitions.put(partitionKey, createContext(regionId, 0L, Long.MAX_VALUE, partitionKey));
    }

    private void createHeavyRegionSlices(Map<String, ExecutionContext> partitions, long regionId, int targetSlices) {
        List<Long> boundaries = quantileBoundaryLookup.apply(regionId, targetSlices);
        if (boundaries == null || boundaries.size() <= 1) {
            log.info("Heavy region {} has insufficient distinct item types (buckets={}) for {} slices; using single partition",
                    regionId, boundaries != null ? boundaries.size() : 0, targetSlices);
            createStandardRegionPartition(partitions, regionId);
            return;
        }

        int sliceCount = boundaries.size();
        for (int i = 0; i < sliceCount; i++) {
            long minTypeIdBound = (i == 0) ? 0L : boundaries.get(i);
            long maxTypeIdBound = (i == sliceCount - 1) ? Long.MAX_VALUE : boundaries.get(i + 1);
            String partitionKey = "partition_region_" + regionId + "_slice_" + i;
            partitions.put(partitionKey, createContext(regionId, minTypeIdBound, maxTypeIdBound, partitionKey));
        }
    }

    private ExecutionContext createContext(long regionId, long minTypeId, long maxTypeId, String partitionKey) {
        ExecutionContext context = new ExecutionContext();
        context.putLong(CONTEXT_REGION_ID, regionId);
        context.putLong(CONTEXT_MIN_TYPE_ID, minTypeId);
        context.putLong(CONTEXT_MAX_TYPE_ID, maxTypeId);
        context.putString(CONTEXT_PARTITION_NAME, partitionKey);
        return context;
    }
}
