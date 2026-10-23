package com.github.serbentd.eve.batch.repository;

import com.github.serbentd.eve.batch.domain.QSdeMetadataEntity;
import com.github.serbentd.eve.batch.domain.SdeMetadataEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SdeMetadataRepositoryTest {

    private final SdeMetadataRepository sdeMetadataRepository;

    SdeMetadataRepositoryTest(SdeMetadataRepository sdeMetadataRepository) {
        this.sdeMetadataRepository = sdeMetadataRepository;
    }

    @Test
    @DisplayName("Should persist and retrieve SDE metadata by sync source and status")
    void shouldPersistAndRetrieveSdeMetadata() {
        Instant t1 = Instant.parse("2026-10-21T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-21T12:00:00Z");

        SdeMetadataEntity olderSync = SdeMetadataEntity.builder()
                .syncSource("ITEMS")
                .sourceUrl("https://example.com/items.csv")
                .etag("\"etag-1\"")
                .lastModified("Wed, 21 Oct 2026 10:00:00 GMT")
                .recordsProcessed(100)
                .recordsInserted(80)
                .recordsUpdated(20)
                .status("SUCCESS")
                .startedAt(t1)
                .completedAt(t1.plusSeconds(30))
                .build();

        SdeMetadataEntity newerSync = SdeMetadataEntity.builder()
                .syncSource("ITEMS")
                .sourceUrl("https://example.com/items.csv")
                .etag("\"etag-2\"")
                .lastModified("Wed, 21 Oct 2026 12:00:00 GMT")
                .recordsProcessed(110)
                .recordsInserted(10)
                .recordsUpdated(100)
                .status("SUCCESS")
                .startedAt(t2)
                .completedAt(t2.plusSeconds(25))
                .build();

        SdeMetadataEntity stationSync = SdeMetadataEntity.builder()
                .syncSource("STATIONS")
                .sourceUrl("https://example.com/stations.csv")
                .etag("\"etag-s1\"")
                .lastModified("Wed, 21 Oct 2026 12:00:00 GMT")
                .recordsProcessed(50)
                .recordsInserted(50)
                .recordsUpdated(0)
                .status("SUCCESS")
                .startedAt(t2)
                .completedAt(t2.plusSeconds(10))
                .build();

        sdeMetadataRepository.saveAllAndFlush(List.of(olderSync, newerSync, stationSync));

        Optional<SdeMetadataEntity> latestItemSuccess = sdeMetadataRepository
                .findFirstBySyncSourceAndStatusOrderByStartedAtDesc("ITEMS", "SUCCESS");

        assertThat(latestItemSuccess).isPresent();
        assertThat(latestItemSuccess.get().getEtag()).isEqualTo("\"etag-2\"");
        assertThat(latestItemSuccess.get().getRecordsProcessed()).isEqualTo(110);

        List<SdeMetadataEntity> itemHistory = sdeMetadataRepository
                .findBySyncSourceOrderByStartedAtDesc("ITEMS");
        assertThat(itemHistory).hasSize(2);
        assertThat(itemHistory.get(0).getEtag()).isEqualTo("\"etag-2\"");
        assertThat(itemHistory.get(1).getEtag()).isEqualTo("\"etag-1\"");

        // QueryDSL verification
        QSdeMetadataEntity q = QSdeMetadataEntity.sdeMetadataEntity;
        Iterable<SdeMetadataEntity> queryDslResult = sdeMetadataRepository.findAll(
                q.syncSource.eq("STATIONS").and(q.status.eq("SUCCESS"))
        );
        assertThat(queryDslResult).hasSize(1);
    }
}
