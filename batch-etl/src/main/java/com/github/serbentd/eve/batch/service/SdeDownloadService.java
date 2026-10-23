package com.github.serbentd.eve.batch.service;

import com.github.serbentd.eve.batch.config.BatchProperties;
import com.github.serbentd.eve.batch.domain.SdeMetadataEntity;
import com.github.serbentd.eve.batch.repository.SdeMetadataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Service responsible for streaming SDE feeds from HTTP endpoints or local resources
 * using HTTP conditional caching (ETag and Last-Modified) and transparent decompression.
 * <p>
 * {@link #downloadStream(String, String)} performs network I/O and is intentionally non-transactional.
 * Audit persistence methods are individually transactional and never wrap network calls.
 */
@Slf4j
@Service
public class SdeDownloadService {

    /**
     * Upper bound of an HTTP error body captured into exception messages for diagnostics.
     */
    private static final int ERROR_BODY_PREVIEW_BYTES = 512;

    private final BatchProperties batchProperties;
    private final SdeMetadataRepository sdeMetadataRepository;
    private final ResourceLoader resourceLoader;
    private final HttpClient httpClient;

    public SdeDownloadService(BatchProperties batchProperties,
                              SdeMetadataRepository sdeMetadataRepository,
                              ResourceLoader resourceLoader,
                              HttpClient httpClient) {
        this.batchProperties = batchProperties;
        this.sdeMetadataRepository = sdeMetadataRepository;
        this.resourceLoader = resourceLoader;
        this.httpClient = httpClient;
    }

    /**
     * Streams an SDE feed from a remote URL or classpath/file resource conditionally.
     * <p>
     * HTTP error responses are surfaced as {@link IOException}; the calling step is responsible for
     * recording the terminal {@code FAILED} audit entry so each failed run is logged exactly once.
     *
     * @param syncSource synchronization feed identifier (e.g. "ITEMS", "STATIONS")
     * @param url        source URL or resource path
     * @return SdeDownloadResult indicating whether data was modified or skipped
     * @throws IOException          on I/O or network failure, or a non-2xx/304 HTTP response
     * @throws InterruptedException if the HTTP request is interrupted
     */
    public SdeDownloadResult downloadStream(String syncSource, String url) throws IOException, InterruptedException {
        // 1. Support local file or classpath URLs for offline tests and development
        if (url.startsWith("classpath:") || url.startsWith("file:")) {
            log.info("Loading SDE data for source '{}' from local resource: {}", syncSource, url);
            InputStream is = resourceLoader.getResource(url).getInputStream();
            InputStream resolvedStream = isGzipUrl(url) ? wrapGzipSafely(is) : is;
            return new SdeDownloadResult.Modified(resolvedStream, null, null);
        }

        // 2. Query last successful sync metadata for cache validation headers
        Optional<SdeMetadataEntity> lastSuccessful = sdeMetadataRepository
                .findFirstBySyncSourceAndStatusOrderByStartedAtDesc(syncSource, "SUCCESS");

        String etag = lastSuccessful.map(SdeMetadataEntity::getEtag).orElse(null);
        String lastModified = lastSuccessful.map(SdeMetadataEntity::getLastModified).orElse(null);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(batchProperties.sde().readTimeout())
                .header("User-Agent", batchProperties.sde().userAgent());

        if (etag != null && !etag.isBlank()) {
            requestBuilder.header("If-None-Match", etag);
        }
        if (lastModified != null && !lastModified.isBlank()) {
            requestBuilder.header("If-Modified-Since", lastModified);
        }

        log.info("Sending conditional GET for SDE '{}' to {} (If-None-Match: {}, If-Modified-Since: {})",
                syncSource, url, etag, lastModified);

        HttpResponse<InputStream> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );

        int statusCode = response.statusCode();
        log.info("SDE '{}' download responded with HTTP {}", syncSource, statusCode);

        if (statusCode == 304) {
            try (InputStream body = response.body()) {
                // Drain and close empty response body
            }
            return new SdeDownloadResult.NotModified(etag, lastModified);
        }

        if (statusCode >= 200 && statusCode < 300) {
            String newEtag = response.headers().firstValue("ETag").orElse(etag);
            String newLastModified = response.headers().firstValue("Last-Modified").orElse(lastModified);
            InputStream stream = response.body();
            if (isGzipResponse(url, response)) {
                stream = wrapGzipSafely(stream);
            }
            return new SdeDownloadResult.Modified(stream, newEtag, newLastModified);
        }

        try (InputStream errBody = response.body()) {
            byte[] errBytes = errBody.readNBytes(ERROR_BODY_PREVIEW_BYTES);
            String errStr = new String(errBytes, StandardCharsets.UTF_8);
            throw new IOException("HTTP " + statusCode + " error downloading SDE from " + url + ": " + errStr);
        }
    }

    /**
     * Records a skipped synchronization cycle when remote content has not changed.
     */
    @Transactional
    public void recordSkippedSync(String syncSource, String url, String etag, String lastModified, Instant startedAt) {
        SdeMetadataEntity meta = SdeMetadataEntity.builder()
                .syncSource(syncSource)
                .sourceUrl(url)
                .etag(etag)
                .lastModified(lastModified)
                .recordsProcessed(0)
                .recordsInserted(0)
                .recordsUpdated(0)
                .status("SKIPPED_NOT_MODIFIED")
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .build();
        sdeMetadataRepository.save(meta);
        log.info("Recorded SKIPPED_NOT_MODIFIED for SDE source '{}'", syncSource);
    }

    /**
     * Records a failed synchronization execution with diagnostic error details.
     * The message is truncated to the persisted column length so the audit write itself cannot fail.
     *
     * @param processed number of rows committed before the failure occurred
     */
    @Transactional
    public void recordFailedSync(String syncSource, String url, int processed, String errorMessage, Instant startedAt) {
        SdeMetadataEntity meta = SdeMetadataEntity.builder()
                .syncSource(syncSource)
                .sourceUrl(url)
                .recordsProcessed(processed)
                .recordsInserted(0)
                .recordsUpdated(0)
                .status("FAILED")
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .errorMessage(truncate(errorMessage, SdeMetadataEntity.ERROR_MESSAGE_MAX_LENGTH))
                .build();
        sdeMetadataRepository.save(meta);
        log.warn("Recorded FAILED for SDE source '{}' after {} committed rows", syncSource, processed);
    }

    /**
     * Records a successful synchronization cycle with total, inserted, and updated row counts.
     */
    @Transactional
    public SdeMetadataEntity recordSuccessfulSync(String syncSource,
                                                 String url,
                                                 String etag,
                                                 String lastModified,
                                                 int processed,
                                                 int inserted,
                                                 int updated,
                                                 Instant startedAt) {
        SdeMetadataEntity meta = SdeMetadataEntity.builder()
                .syncSource(syncSource)
                .sourceUrl(url)
                .etag(etag)
                .lastModified(lastModified)
                .recordsProcessed(processed)
                .recordsInserted(inserted)
                .recordsUpdated(updated)
                .status("SUCCESS")
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .build();
        SdeMetadataEntity saved = sdeMetadataRepository.save(meta);
        log.info("Recorded SUCCESS for SDE source '{}': {} processed ({} inserted, {} updated)",
                syncSource, processed, inserted, updated);
        return saved;
    }

    /**
     * Wraps a raw stream in {@link GZIPInputStream}, closing the raw stream if the GZIP header
     * is corrupt or truncated (the constructor reads the header eagerly and may throw).
     */
    private static InputStream wrapGzipSafely(InputStream rawStream) throws IOException {
        try {
            return new GZIPInputStream(rawStream);
        } catch (IOException e) {
            try {
                rawStream.close();
            } catch (IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static boolean isGzipUrl(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).endsWith(".gz");
    }

    private static boolean isGzipResponse(String url, HttpResponse<?> response) {
        if (isGzipUrl(url)) {
            return true;
        }
        return response.headers()
                .firstValue("Content-Encoding")
                .map(enc -> enc.toLowerCase(Locale.ROOT).contains("gzip"))
                .orElse(false);
    }
}
