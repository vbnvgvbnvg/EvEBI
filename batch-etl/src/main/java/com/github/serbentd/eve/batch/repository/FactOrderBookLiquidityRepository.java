package com.github.serbentd.eve.batch.repository;

import com.github.serbentd.eve.batch.domain.FactOrderBookLiquidityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FactOrderBookLiquidityRepository extends JpaRepository<FactOrderBookLiquidityEntity, Long>, QuerydslPredicateExecutor<FactOrderBookLiquidityEntity> {

    Optional<FactOrderBookLiquidityEntity> findByDateIdAndTimeIdAndLocationIdAndTypeId(Integer dateId, Integer timeId, Long locationId, Long typeId);
}
