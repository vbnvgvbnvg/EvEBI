package com.github.serbentd.eve.batch.repository;

import com.github.serbentd.eve.batch.domain.StagingMarketOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface StagingMarketOrderRepository extends JpaRepository<StagingMarketOrderEntity, Long>, QuerydslPredicateExecutor<StagingMarketOrderEntity> {

    List<StagingMarketOrderEntity> findByRegionIdAndIssuedBetween(Long regionId, Instant start, Instant end);

    @Query("SELECT DISTINCT o.regionId FROM StagingMarketOrderEntity o WHERE o.issued >= :start AND o.issued < :end ORDER BY o.regionId")
    List<Long> findDistinctRegionIdsByIssuedBetween(@Param("start") Instant start, @Param("end") Instant end);

    @Query("SELECT DISTINCT o.regionId FROM StagingMarketOrderEntity o WHERE o.isActive = true AND o.status = 'ACTIVE' ORDER BY o.regionId")
    List<Long> findDistinctActiveRegionIds();

    /**
     * Computes {@code type_id} quantile boundary start points for a heavy region by dividing its
     * distinct active item types into {@code slices} equal-count buckets via {@code NTILE}.
     * <p>
     * Works identically on PostgreSQL and H2 in PostgreSQL compatibility mode, and leverages the
     * composite index {@code (region_id, type_id, ...)}.
     */
    @Query(value = """
            SELECT MIN(type_id) AS slice_min_type_id
            FROM (
                SELECT type_id, NTILE(:slices) OVER (ORDER BY type_id) AS bucket
                FROM (
                    SELECT DISTINCT type_id
                    FROM staging.market_orders
                    WHERE region_id = :regionId
                ) distinct_types
            ) bucketed
            GROUP BY bucket
            ORDER BY bucket
            """, nativeQuery = true)
    List<Long> findTypeIdQuantileBoundaries(@Param("regionId") Long regionId, @Param("slices") int slices);
}
