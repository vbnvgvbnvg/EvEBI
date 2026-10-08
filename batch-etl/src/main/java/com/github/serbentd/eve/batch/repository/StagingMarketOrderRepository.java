package com.github.serbentd.eve.batch.repository;

import com.github.serbentd.eve.batch.domain.StagingMarketOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface StagingMarketOrderRepository extends JpaRepository<StagingMarketOrderEntity, Long>, QuerydslPredicateExecutor<StagingMarketOrderEntity> {

    List<StagingMarketOrderEntity> findByRegionIdAndIssuedBetween(Long regionId, Instant start, Instant end);
}
