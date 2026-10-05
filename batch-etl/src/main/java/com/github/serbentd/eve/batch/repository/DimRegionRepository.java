package com.github.serbentd.eve.batch.repository;

import com.github.serbentd.eve.batch.domain.DimRegionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DimRegionRepository extends JpaRepository<DimRegionEntity, Long>, QuerydslPredicateExecutor<DimRegionEntity> {

    Optional<DimRegionEntity> findByRegionName(String regionName);
}
