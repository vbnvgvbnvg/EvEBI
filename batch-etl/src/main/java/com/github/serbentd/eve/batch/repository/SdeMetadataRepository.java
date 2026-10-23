package com.github.serbentd.eve.batch.repository;

import com.github.serbentd.eve.batch.domain.SdeMetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SdeMetadataRepository extends JpaRepository<SdeMetadataEntity, Long>, QuerydslPredicateExecutor<SdeMetadataEntity> {

    Optional<SdeMetadataEntity> findFirstBySyncSourceAndStatusOrderByStartedAtDesc(String syncSource, String status);

    Optional<SdeMetadataEntity> findFirstBySyncSourceOrderByStartedAtDesc(String syncSource);

    List<SdeMetadataEntity> findBySyncSourceOrderByStartedAtDesc(String syncSource);
}
