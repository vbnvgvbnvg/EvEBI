package com.github.serbentd.eve.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "sde_metadata", schema = "analytics")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class SdeMetadataEntity {

    /**
     * Maximum persisted length of {@code error_message}; mirrors the Liquibase column definition.
     */
    public static final int ERROR_MESSAGE_MAX_LENGTH = 2048;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sync_id", nullable = false)
    private Long syncId;

    @Column(name = "sync_source", nullable = false, length = 64)
    private String syncSource;

    @Column(name = "source_url", nullable = false, length = 1024)
    private String sourceUrl;

    @Column(name = "etag", length = 255)
    private String etag;

    @Column(name = "last_modified", length = 255)
    private String lastModified;

    @Column(name = "records_processed", nullable = false)
    private Integer recordsProcessed;

    @Column(name = "records_inserted", nullable = false)
    private Integer recordsInserted;

    @Column(name = "records_updated", nullable = false)
    private Integer recordsUpdated;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", length = ERROR_MESSAGE_MAX_LENGTH)
    private String errorMessage;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SdeMetadataEntity that)) return false;
        if (syncId != null && that.syncId != null) {
            return Objects.equals(syncId, that.syncId);
        }
        return Objects.equals(syncSource, that.syncSource)
                && Objects.equals(startedAt, that.startedAt);
    }

    @Override
    public int hashCode() {
        if (syncId != null) {
            return Objects.hashCode(syncId);
        }
        return Objects.hash(syncSource, startedAt);
    }
}
