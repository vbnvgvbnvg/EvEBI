package com.github.serbentd.eve.batch.repository;

import com.github.serbentd.eve.batch.domain.DimDateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface DimDateRepository extends JpaRepository<DimDateEntity, Integer>, QuerydslPredicateExecutor<DimDateEntity> {

    Optional<DimDateEntity> findByFullDate(LocalDate fullDate);
}
