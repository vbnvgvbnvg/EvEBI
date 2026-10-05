package com.github.serbentd.eve.batch.repository;

import com.github.serbentd.eve.batch.domain.DimItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DimItemRepository extends JpaRepository<DimItemEntity, Long>, QuerydslPredicateExecutor<DimItemEntity> {

    Optional<DimItemEntity> findByTypeName(String typeName);

    List<DimItemEntity> findByGroupId(Long groupId);

    List<DimItemEntity> findByCategoryId(Long categoryId);
}
