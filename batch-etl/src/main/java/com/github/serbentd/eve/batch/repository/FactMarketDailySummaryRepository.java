package com.github.serbentd.eve.batch.repository;

import com.github.serbentd.eve.batch.domain.FactMarketDailySummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FactMarketDailySummaryRepository extends JpaRepository<FactMarketDailySummaryEntity, Long>, QuerydslPredicateExecutor<FactMarketDailySummaryEntity> {

    Optional<FactMarketDailySummaryEntity> findByDateIdAndRegionIdAndTypeId(Integer dateId, Long regionId, Long typeId);
}
