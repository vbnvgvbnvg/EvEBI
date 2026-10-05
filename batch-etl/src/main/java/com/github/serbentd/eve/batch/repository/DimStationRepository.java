package com.github.serbentd.eve.batch.repository;

import com.github.serbentd.eve.batch.domain.DimStationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DimStationRepository extends JpaRepository<DimStationEntity, Long>, QuerydslPredicateExecutor<DimStationEntity> {

    List<DimStationEntity> findByRegionId(Long regionId);

    List<DimStationEntity> findBySolarSystemId(Long solarSystemId);
}
