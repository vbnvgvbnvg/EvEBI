package com.github.serbentd.eve.batch.service;

import com.github.serbentd.eve.batch.config.BatchProperties;
import com.github.serbentd.eve.batch.domain.SdeMetadataEntity;
import com.github.serbentd.eve.batch.repository.SdeMetadataRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SdeDownloadServiceTest {

    private static final String ITEMS_URL = "https://example.com/items.csv";
    private static final String STATIONS_URL = "https://example.com/stations.csv";

    private final SdeMetadataRepository sdeMetadataRepository;
    private final ResourceLoader resourceLoader;
    private final HttpClient httpClient;
    private final HttpResponse<InputStream> httpResponse;
    private final SdeDownloadService downloadService;

    SdeDownloadServiceTest(@Mock SdeMetadataRepository sdeMetadataRepository,
                           @Mock ResourceLoader resourceLoader,
                           @Mock HttpClient httpClient,
                           @Mock HttpResponse<InputStream> httpResponse) {
        this.sdeMetadataRepository = sdeMetadataRepository;
        this.resourceLoader = resourceLoader;
        this.httpClient = httpClient;
        this.httpResponse = httpResponse;

        BatchProperties batchProperties = new BatchProperties(
                new BatchProperties.ChunkSizeProperties(500, 1000),
                new BatchProperties.SdeProperties(
                        ITEMS_URL,
                        STATIONS_URL,
                        "EveBI-Test/1.0",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        500
                )
        );
        this.downloadService = new SdeDownloadService(
                batchProperties,
                sdeMetadataRepository,
                resourceLoader,
                httpClient
        );
    }

    @Test
    @DisplayName("Should load uncompressed stream directly from local classpath resource")
    void shouldLoadLocalClasspathResource() throws Exception {
        String csvContent = "type_id,type_name\n34,Tritanium";
        when(resourceLoader.getResource("classpath:sde/items.csv"))
                .thenReturn(new ByteArrayResource(csvContent.getBytes(StandardCharsets.UTF_8)));

        SdeDownloadResult result = downloadService.downloadStream("ITEMS", "classpath:sde/items.csv");

        assertThat(result).isInstanceOf(SdeDownloadResult.Modified.class);
        SdeDownloadResult.Modified modified = (SdeDownloadResult.Modified) result;
        try (modified) {
            String readContent = new String(modified.inputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(readContent).isEqualTo(csvContent);
            assertThat(modified.etag()).isNull();
            assertThat(modified.lastModified()).isNull();
        }
    }

    @Test
    @DisplayName("Should transparently decompress gzipped classpath resource")
    void shouldLoadGzipClasspathResource() throws Exception {
        String csvContent = "type_id,type_name\n34,Tritanium";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(csvContent.getBytes(StandardCharsets.UTF_8));
        }

        when(resourceLoader.getResource("classpath:sde/items.csv.gz"))
                .thenReturn(new ByteArrayResource(baos.toByteArray()));

        SdeDownloadResult result = downloadService.downloadStream("ITEMS", "classpath:sde/items.csv.gz");

        assertThat(result).isInstanceOf(SdeDownloadResult.Modified.class);
        SdeDownloadResult.Modified modified = (SdeDownloadResult.Modified) result;
        try (modified) {
            String decompressed = new String(modified.inputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(decompressed).isEqualTo(csvContent);
        }
    }

    @Test
    @DisplayName("Should close the raw stream when a .gz resource has a corrupt GZIP header")
    void shouldCloseRawStreamOnCorruptGzipHeader() {
        CloseTrackingInputStream rawStream = new CloseTrackingInputStream(
                "definitely not gzip".getBytes(StandardCharsets.UTF_8));
        when(resourceLoader.getResource("classpath:sde/corrupt.csv.gz"))
                .thenReturn(new ByteArrayResource(new byte[0]) {
                    @Override
                    public InputStream getInputStream() {
                        return rawStream;
                    }
                });

        assertThatThrownBy(() -> downloadService.downloadStream("ITEMS", "classpath:sde/corrupt.csv.gz"))
                .isInstanceOf(IOException.class);
        assertThat(rawStream.isClosed()).isTrue();
    }

    @Test
    @DisplayName("Should send conditional headers (If-None-Match, If-Modified-Since) and return Modified on HTTP 200")
    void shouldSendConditionalHeadersAndReturnModifiedOn200() throws Exception {
        SdeMetadataEntity lastSuccess = SdeMetadataEntity.builder()
                .syncSource("ITEMS")
                .etag("\"old-etag\"")
                .lastModified("Wed, 21 Oct 2026 10:00:00 GMT")
                .build();
        when(sdeMetadataRepository.findFirstBySyncSourceAndStatusOrderByStartedAtDesc("ITEMS", "SUCCESS"))
                .thenReturn(Optional.of(lastSuccess));

        String payload = "type_id,type_name\n35,Pyerite";
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
        when(httpResponse.headers()).thenReturn(HttpHeaders.of(
                Map.of(
                        "ETag", List.of("\"new-etag\""),
                        "Last-Modified", List.of("Wed, 21 Oct 2026 12:00:00 GMT")
                ),
                (k, v) -> true
        ));
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        SdeDownloadResult result = downloadService.downloadStream("ITEMS", ITEMS_URL);

        assertThat(result).isInstanceOf(SdeDownloadResult.Modified.class);
        SdeDownloadResult.Modified modified = (SdeDownloadResult.Modified) result;
        try (modified) {
            assertThat(modified.etag()).isEqualTo("\"new-etag\"");
            assertThat(modified.lastModified()).isEqualTo("Wed, 21 Oct 2026 12:00:00 GMT");
            String body = new String(modified.inputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).isEqualTo(payload);
        }

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());
        HttpRequest request = requestCaptor.getValue();
        assertThat(request.headers().firstValue("If-None-Match")).contains("\"old-etag\"");
        assertThat(request.headers().firstValue("If-Modified-Since")).contains("Wed, 21 Oct 2026 10:00:00 GMT");
        assertThat(request.headers().firstValue("User-Agent")).contains("EveBI-Test/1.0");
    }

    @Test
    @DisplayName("Should return NotModified on HTTP 304 without writing audit during download")
    void shouldReturnNotModifiedOn304() throws Exception {
        SdeMetadataEntity lastSuccess = SdeMetadataEntity.builder()
                .syncSource("STATIONS")
                .etag("\"station-etag\"")
                .lastModified("Wed, 21 Oct 2026 10:00:00 GMT")
                .build();
        when(sdeMetadataRepository.findFirstBySyncSourceAndStatusOrderByStartedAtDesc("STATIONS", "SUCCESS"))
                .thenReturn(Optional.of(lastSuccess));

        when(httpResponse.statusCode()).thenReturn(304);
        when(httpResponse.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        SdeDownloadResult result = downloadService.downloadStream("STATIONS", STATIONS_URL);

        assertThat(result).isInstanceOf(SdeDownloadResult.NotModified.class);
        SdeDownloadResult.NotModified notModified = (SdeDownloadResult.NotModified) result;
        assertThat(notModified.etag()).isEqualTo("\"station-etag\"");
        assertThat(notModified.lastModified()).isEqualTo("Wed, 21 Oct 2026 10:00:00 GMT");

        verify(sdeMetadataRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should persist SKIPPED_NOT_MODIFIED audit log in recordSkippedSync")
    void shouldRecordSkippedSync() {
        downloadService.recordSkippedSync(
                "STATIONS",
                STATIONS_URL,
                "\"station-etag\"",
                "Wed, 21 Oct 2026 10:00:00 GMT",
                Instant.now().minusSeconds(5)
        );

        ArgumentCaptor<SdeMetadataEntity> metaCaptor = ArgumentCaptor.forClass(SdeMetadataEntity.class);
        verify(sdeMetadataRepository).save(metaCaptor.capture());
        SdeMetadataEntity saved = metaCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("SKIPPED_NOT_MODIFIED");
        assertThat(saved.getSyncSource()).isEqualTo("STATIONS");
        assertThat(saved.getRecordsProcessed()).isZero();
    }

    @Test
    @DisplayName("Should throw IOException with UTF-8 decoded body on HTTP 500, leaving FAILED audit to the step")
    void shouldThrowExceptionOn500WithoutDuplicateAudit() throws Exception {
        when(sdeMetadataRepository.findFirstBySyncSourceAndStatusOrderByStartedAtDesc("ITEMS", "SUCCESS"))
                .thenReturn(Optional.empty());

        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn(new ByteArrayInputStream(
                "Internal Server Error \u2013 \u03a9".getBytes(StandardCharsets.UTF_8)));
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        assertThatThrownBy(() -> downloadService.downloadStream("ITEMS", ITEMS_URL))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("Internal Server Error \u2013 \u03a9");

        verify(sdeMetadataRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should persist SUCCESS audit log in recordSuccessfulSync")
    void shouldRecordSuccessfulSync() {
        downloadService.recordSuccessfulSync(
                "ITEMS",
                ITEMS_URL,
                "\"etag-abc\"",
                "Wed, 21 Oct 2026 12:00:00 GMT",
                1000,
                200,
                800,
                Instant.now().minusSeconds(15)
        );

        ArgumentCaptor<SdeMetadataEntity> metaCaptor = ArgumentCaptor.forClass(SdeMetadataEntity.class);
        verify(sdeMetadataRepository).save(metaCaptor.capture());
        SdeMetadataEntity saved = metaCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
        assertThat(saved.getSyncSource()).isEqualTo("ITEMS");
        assertThat(saved.getRecordsProcessed()).isEqualTo(1000);
        assertThat(saved.getRecordsInserted()).isEqualTo(200);
        assertThat(saved.getRecordsUpdated()).isEqualTo(800);
    }

    @Test
    @DisplayName("Should persist FAILED audit log and truncate oversized error messages to the column length")
    void shouldRecordFailedSyncAndTruncateMessage() {
        String oversizedMessage = "x".repeat(SdeMetadataEntity.ERROR_MESSAGE_MAX_LENGTH + 500);

        downloadService.recordFailedSync("STATIONS", STATIONS_URL, 42, oversizedMessage, Instant.now());

        ArgumentCaptor<SdeMetadataEntity> metaCaptor = ArgumentCaptor.forClass(SdeMetadataEntity.class);
        verify(sdeMetadataRepository).save(metaCaptor.capture());
        SdeMetadataEntity saved = metaCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getRecordsProcessed()).isEqualTo(42);
        assertThat(saved.getErrorMessage()).hasSize(SdeMetadataEntity.ERROR_MESSAGE_MAX_LENGTH);
    }

    /**
     * Minimal stream double that records whether {@link #close()} was invoked.
     */
    private static final class CloseTrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        CloseTrackingInputStream(byte[] content) {
            super(content);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        boolean isClosed() {
            return closed;
        }
    }
}
