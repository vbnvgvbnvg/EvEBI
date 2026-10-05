package com.github.serbentd.eve.batch.repository;

import com.github.serbentd.eve.batch.domain.DimTimeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DimTimeRepository extends JpaRepository<DimTimeEntity, Integer>, QuerydslPredicateExecutor<DimTimeEntity> {

    Optional<DimTimeEntity> findByTimeValue(LocalTime timeValue);

    List<DimTimeEntity> findByEveTimeBucket(String eveTimeBucket);
}
