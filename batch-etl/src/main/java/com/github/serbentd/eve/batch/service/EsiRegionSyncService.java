package com.github.serbentd.eve.batch.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.serbentd.eve.batch.config.BatchProperties;
import com.github.serbentd.eve.batch.dto.EsiUniverseNameDto;
import com.github.serbentd.eve.batch.job.sde.SqlDialect;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Service responsible for programmatically synchronizing active EVE Online market regions
 * from CCP's ESI Universe API into {@code analytics.dim_region}.
 * <p>
 * Network I/O is strictly isolated from database transactions. Upserts are executed in a single
 * atomic batch utilizing dialect-aware SQL (PostgreSQL {@code ON CONFLICT DO UPDATE} and H2 {@code MERGE INTO}).
 */
@Slf4j
@Service
public class EsiRegionSyncService {

    private static final String CATEGORY_REGION = "region";

    private static final String PG_UPSERT_SQL = """
            INSERT INTO analytics.dim_region (
                region_id, region_name, description, created_at, updated_at
            ) VALUES (
                :regionId, :regionName, :description, :createdAt, :updatedAt
            )
            ON CONFLICT (region_id) DO UPDATE SET
                region_name = EXCLUDED.region_name,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String H2_UPSERT_SQL = """
            MERGE INTO analytics.dim_region (
                region_id, region_name, description, created_at, updated_at
            ) KEY(region_id) VALUES (
                :regionId, :regionName, :description,
                COALESCE((SELECT existing.created_at FROM analytics.dim_region existing
                          WHERE existing.region_id = :regionId), :createdAt),
                :updatedAt
            )
            """;

    private final BatchProperties batchProperties;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final HttpClient httpClient;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final SdeDownloadService sdeDownloadService;
    private final String upsertSql;
    private final java.util.concurrent.atomic.AtomicBoolean isSyncing = new java.util.concurrent.atomic.AtomicBoolean(false);

    public EsiRegionSyncService(
            BatchProperties batchProperties,
            DataSource dataSource,
            @Qualifier("esiHttpClient") HttpClient httpClient,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            SdeDownloadService sdeDownloadService) {
        this.batchProperties = batchProperties;
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        this.httpClient = httpClient;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.sdeDownloadService = sdeDownloadService;

        SqlDialect dialect = SqlDialect.detect(dataSource);
        this.upsertSql = dialect == SqlDialect.POSTGRESQL ? PG_UPSERT_SQL : H2_UPSERT_SQL;
        log.info("EsiRegionSyncService initialized with {} dialect", dialect);
    }

    /**
     * Programmatically fetches all active universe regions from ESI, filters to valid market regions,
     * resolves official names in bulk, and upserts them into {@code analytics.dim_region}.
     *
     * @return the number of synchronized regions
     */
    public int syncRegions() {
        if (!isSyncing.compareAndSet(false, true)) {
            log.warn("ESI region synchronization is already in progress, rejecting concurrent execution");
            throw new IllegalStateException("ESI region synchronization is already in progress");
        }
        try {
            return doSyncRegions();
        } finally {
            isSyncing.set(false);
        }
    }

    private int doSyncRegions() {
        Instant startedAt = Instant.now();
        String regionsUrl = batchProperties.esi().regionsUrl();
        String namesUrl = batchProperties.esi().namesUrl();

        log.info("Starting programmatic ESI region synchronization from {}", regionsUrl);

        try {
            // 1. Fetch all region IDs
            List<Long> allRegionIds = fetchRegionIds(regionsUrl);
            Long minId = batchProperties.esi().minRegionId();
            Long maxId = batchProperties.esi().maxRegionId();

            List<Long> marketRegionIds = allRegionIds.stream()
                    .filter(id -> id != null && id >= minId && id < maxId)
                    .sorted()
                    .toList();

            if (marketRegionIds.isEmpty()) {
                log.warn("Zero market regions discovered within range [{}, {})", minId, maxId);
                return 0;
            }

            log.info("Discovered {} valid market regions from {} total regions; resolving names via {}",
                    marketRegionIds.size(), allRegionIds.size(), namesUrl);

            // 2. Bulk resolve names
            List<EsiUniverseNameDto> resolvedNames = resolveNames(namesUrl, marketRegionIds);
            List<EsiUniverseNameDto> validRegions = resolvedNames.stream()
                    .filter(dto -> dto != null && dto.id() != null && dto.name() != null)
                    .filter(dto -> CATEGORY_REGION.equalsIgnoreCase(dto.category()))
                    .toList();

            if (validRegions.isEmpty()) {
                log.warn("ESI bulk names resolution returned zero valid region records");
                return 0;
            }

            // 3. Batch upsert into analytics.dim_region
            Timestamp now = Timestamp.from(startedAt);
            SqlParameterSource[] batchParams = validRegions.stream()
                    .map(dto -> new MapSqlParameterSource()
                            .addValue("regionId", dto.id())
                            .addValue("regionName", dto.name())
                            .addValue("description", null)
                            .addValue("createdAt", now)
                            .addValue("updatedAt", now))
                    .toArray(SqlParameterSource[]::new);

            jdbcTemplate.batchUpdate(upsertSql, batchParams);
            log.info("Successfully synchronized and upserted {} regions into analytics.dim_region", validRegions.size());

            // 4. Record audit metadata
            sdeDownloadService.recordSuccessfulSync(
                    "REGIONS",
                    regionsUrl,
                    null,
                    null,
                    validRegions.size(),
                    validRegions.size(),
                    0,
                    startedAt
            );

            return validRegions.size();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while synchronizing ESI regions: {}", e.getMessage(), e);
            sdeDownloadService.recordFailedSync(
                    "REGIONS",
                    regionsUrl,
                    0,
                    e.getMessage(),
                    startedAt
            );
            throw new IllegalStateException("Interrupted while synchronizing ESI regions: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to synchronize ESI regions: {}", e.getMessage(), e);
            sdeDownloadService.recordFailedSync(
                    "REGIONS",
                    regionsUrl,
                    0,
                    e.getMessage(),
                    startedAt
            );
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Failed to synchronize ESI regions: " + e.getMessage(), e);
        }
    }

    private List<Long> fetchRegionIds(String url) throws IOException, InterruptedException {
        if (isLocalResource(url)) {
            try (InputStream is = resourceLoader.getResource(url).getInputStream()) {
                return objectMapper.readValue(is, new TypeReference<List<Long>>() {});
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", batchProperties.esi().userAgent())
                .timeout(batchProperties.esi().readTimeout())
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream body = response.body()) {
                String err = new String(body.readNBytes(512), StandardCharsets.UTF_8);
                throw new IOException("HTTP " + response.statusCode() + " fetching regions from " + url + ": " + err);
            }
        }

        try (InputStream is = response.body()) {
            return objectMapper.readValue(is, new TypeReference<List<Long>>() {});
        }
    }

    private List<EsiUniverseNameDto> resolveNames(String url, List<Long> regionIds) throws IOException, InterruptedException {
        if (isLocalResource(url)) {
            try (InputStream is = resourceLoader.getResource(url).getInputStream()) {
                return objectMapper.readValue(is, new TypeReference<List<EsiUniverseNameDto>>() {});
            }
        }

        byte[] requestBody = objectMapper.writeValueAsBytes(regionIds);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", batchProperties.esi().userAgent())
                .header("Content-Type", "application/json")
                .timeout(batchProperties.esi().readTimeout())
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream body = response.body()) {
                String err = new String(body.readNBytes(512), StandardCharsets.UTF_8);
                throw new IOException("HTTP " + response.statusCode() + " resolving names from " + url + ": " + err);
            }
        }

        try (InputStream is = response.body()) {
            return objectMapper.readValue(is, new TypeReference<List<EsiUniverseNameDto>>() {});
        }
    }

    private static boolean isLocalResource(String url) {
        return url != null && (url.startsWith("classpath:") || url.startsWith("file:"));
    }
}
