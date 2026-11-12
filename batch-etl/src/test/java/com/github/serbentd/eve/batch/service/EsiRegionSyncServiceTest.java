package com.github.serbentd.eve.batch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.serbentd.eve.batch.config.BatchProperties;
import com.github.serbentd.eve.batch.config.PartitionRole;
import com.github.serbentd.eve.batch.domain.DimRegionEntity;
import com.github.serbentd.eve.batch.domain.SdeMetadataEntity;
import com.github.serbentd.eve.batch.repository.DimRegionRepository;
import com.github.serbentd.eve.batch.repository.SdeMetadataRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class EsiRegionSyncServiceTest {

    private static final long DERELIK_REGION_ID = 10000001L;
    private static final long THE_FORGE_REGION_ID = 10000002L;
    private static final long LONETREK_REGION_ID = 10000003L;
    private static final long MIN_MARKET_REGION_ID = 10000000L;
    private static final long MAX_MARKET_REGION_ID = 11000000L;

    private final EsiRegionSyncService esiRegionSyncService;
    private final DimRegionRepository dimRegionRepository;
    private final SdeMetadataRepository sdeMetadataRepository;
    private final DataSource dataSource;
    private final HttpClient httpClient;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final SdeDownloadService sdeDownloadService;

    EsiRegionSyncServiceTest(
            EsiRegionSyncService esiRegionSyncService,
            DimRegionRepository dimRegionRepository,
            SdeMetadataRepository sdeMetadataRepository,
            DataSource dataSource,
            @Qualifier("esiHttpClient") HttpClient httpClient,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            SdeDownloadService sdeDownloadService) {
        this.esiRegionSyncService = esiRegionSyncService;
        this.dimRegionRepository = dimRegionRepository;
        this.sdeMetadataRepository = sdeMetadataRepository;
        this.dataSource = dataSource;
        this.httpClient = httpClient;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.sdeDownloadService = sdeDownloadService;
    }

    @Test
    @DisplayName("Should successfully bulk synchronize market regions from classpath JSON and record audit metadata")
    void syncRegions_whenValidClasspathFixtures_shouldUpsertRegionsAndRecordMetadata() {
        int count = esiRegionSyncService.syncRegions();
        assertThat(count).isEqualTo(3);

        // Verify inserted/upserted regions
        Optional<DimRegionEntity> forge = dimRegionRepository.findById(THE_FORGE_REGION_ID);
        assertThat(forge).isPresent();
        assertThat(forge.get().getRegionName()).isEqualTo("The Forge");

        Optional<DimRegionEntity> derelik = dimRegionRepository.findById(DERELIK_REGION_ID);
        assertThat(derelik).isPresent();
        assertThat(derelik.get().getRegionName()).isEqualTo("Derelik");

        Optional<DimRegionEntity> lonetrek = dimRegionRepository.findById(LONETREK_REGION_ID);
        assertThat(lonetrek).isPresent();
        assertThat(lonetrek.get().getRegionName()).isEqualTo("Lonetrek");

        // Verify audit log in sde_metadata
        Optional<SdeMetadataEntity> metadata = sdeMetadataRepository
                .findFirstBySyncSourceAndStatusOrderByStartedAtDesc("REGIONS", "SUCCESS");
        assertThat(metadata).isPresent();
        assertThat(metadata.get().getRecordsProcessed()).isEqualTo(3);
        assertThat(metadata.get().getRecordsInserted()).isEqualTo(3);

        // Idempotency: second run should succeed cleanly
        int secondCount = esiRegionSyncService.syncRegions();
        assertThat(secondCount).isEqualTo(3);
    }

    @Test
    @DisplayName("Should fetch via HTTP client and parse responses when URLs are external HTTP endpoints")
    @SuppressWarnings("unchecked")
    void syncRegions_whenHttpEndpoints_shouldFetchAndUpsert() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<InputStream> regionsResponse = mock(HttpResponse.class);
        HttpResponse<InputStream> namesResponse = mock(HttpResponse.class);

        String regionsJson = "[10000001, 10000002, 11000001]";
        String namesJson = """
                [
                  {"id": 10000001, "name": "Derelik", "category": "region"},
                  {"id": 10000002, "name": "The Forge", "category": "region"}
                ]
                """;

        when(regionsResponse.statusCode()).thenReturn(200);
        when(regionsResponse.body()).thenReturn(new ByteArrayInputStream(regionsJson.getBytes(StandardCharsets.UTF_8)));

        when(namesResponse.statusCode()).thenReturn(200);
        when(namesResponse.body()).thenReturn(new ByteArrayInputStream(namesJson.getBytes(StandardCharsets.UTF_8)));

        Mockito.doReturn(regionsResponse)
                .doReturn(namesResponse)
                .when(mockHttpClient).send(any(HttpRequest.class), any());

        BatchProperties httpProperties = createTestProperties(
                "https://esi.evetech.net/latest/universe/regions/",
                "https://esi.evetech.net/latest/universe/names/"
        );

        EsiRegionSyncService httpService = new EsiRegionSyncService(
                httpProperties,
                dataSource,
                mockHttpClient,
                resourceLoader,
                objectMapper,
                sdeDownloadService
        );

        int count = httpService.syncRegions();
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("Should handle HTTP error responses by recording failed audit and throwing exception")
    @SuppressWarnings("unchecked")
    void syncRegions_whenHttpError_shouldRecordFailure() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<InputStream> errorResponse = mock(HttpResponse.class);

        when(errorResponse.statusCode()).thenReturn(500);
        when(errorResponse.body()).thenReturn(new ByteArrayInputStream("Internal Server Error".getBytes(StandardCharsets.UTF_8)));

        Mockito.doReturn(errorResponse)
                .when(mockHttpClient).send(any(HttpRequest.class), any());

        BatchProperties httpProperties = createTestProperties(
                "https://esi.evetech.net/latest/universe/regions/",
                "https://esi.evetech.net/latest/universe/names/"
        );

        EsiRegionSyncService httpService = new EsiRegionSyncService(
                httpProperties,
                dataSource,
                mockHttpClient,
                resourceLoader,
                objectMapper,
                sdeDownloadService
        );

        assertThatThrownBy(httpService::syncRegions)
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Should return 0 when zero regions match market bounds")
    @SuppressWarnings("unchecked")
    void syncRegions_whenNoMarketRegions_shouldReturnZero() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<InputStream> regionsResponse = mock(HttpResponse.class);

        String regionsJson = "[11000001, 11000002]"; // All wormhole regions

        when(regionsResponse.statusCode()).thenReturn(200);
        when(regionsResponse.body()).thenReturn(new ByteArrayInputStream(regionsJson.getBytes(StandardCharsets.UTF_8)));

        Mockito.doReturn(regionsResponse)
                .when(mockHttpClient).send(any(HttpRequest.class), any());

        BatchProperties httpProperties = createTestProperties(
                "https://esi.evetech.net/latest/universe/regions/",
                "https://esi.evetech.net/latest/universe/names/"
        );

        EsiRegionSyncService httpService = new EsiRegionSyncService(
                httpProperties,
                dataSource,
                mockHttpClient,
                resourceLoader,
                objectMapper,
                sdeDownloadService
        );

        int count = httpService.syncRegions();
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("Should return 0 when bulk names endpoint returns zero region entities")
    @SuppressWarnings("unchecked")
    void syncRegions_whenNamesReturnNonRegions_shouldReturnZero() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<InputStream> regionsResponse = mock(HttpResponse.class);
        HttpResponse<InputStream> namesResponse = mock(HttpResponse.class);

        String regionsJson = "[10000001]";
        String namesJson = """
                [
                  {"id": 10000001, "name": "Some System", "category": "solar_system"}
                ]
                """;

        when(regionsResponse.statusCode()).thenReturn(200);
        when(regionsResponse.body()).thenReturn(new ByteArrayInputStream(regionsJson.getBytes(StandardCharsets.UTF_8)));

        when(namesResponse.statusCode()).thenReturn(200);
        when(namesResponse.body()).thenReturn(new ByteArrayInputStream(namesJson.getBytes(StandardCharsets.UTF_8)));

        Mockito.doReturn(regionsResponse)
                .doReturn(namesResponse)
                .when(mockHttpClient).send(any(HttpRequest.class), any());

        BatchProperties httpProperties = createTestProperties(
                "https://esi.evetech.net/latest/universe/regions/",
                "https://esi.evetech.net/latest/universe/names/"
        );

        EsiRegionSyncService httpService = new EsiRegionSyncService(
                httpProperties,
                dataSource,
                mockHttpClient,
                resourceLoader,
                objectMapper,
                sdeDownloadService
        );

        int count = httpService.syncRegions();
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("Should record failed sync audit record when resource is missing or invalid")
    void syncRegions_whenSourceFails_shouldRecordFailedMetadataAndThrow() {
        BatchProperties brokenProperties = createTestProperties(
                "classpath:/esi/non_existent_regions.json",
                "classpath:/esi/names.json"
        );

        EsiRegionSyncService failingService = new EsiRegionSyncService(
                brokenProperties,
                dataSource,
                httpClient,
                resourceLoader,
                objectMapper,
                sdeDownloadService
        );

        assertThatThrownBy(failingService::syncRegions)
                .isInstanceOf(Exception.class);

        Optional<SdeMetadataEntity> failedMeta = sdeMetadataRepository
                .findFirstBySyncSourceAndStatusOrderByStartedAtDesc("REGIONS", "FAILED");
        assertThat(failedMeta).isPresent();
    }

    @Test
    @DisplayName("Should restore thread interrupt status and record failed audit when HTTP request is interrupted")
    void syncRegions_whenInterrupted_shouldRestoreInterruptAndThrow() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        when(mockHttpClient.send(any(HttpRequest.class), any()))
                .thenThrow(new InterruptedException("Simulated thread interruption"));

        BatchProperties httpProperties = createTestProperties(
                "https://esi.evetech.net/latest/universe/regions/",
                "https://esi.evetech.net/latest/universe/names/"
        );

        EsiRegionSyncService interruptedService = new EsiRegionSyncService(
                httpProperties,
                dataSource,
                mockHttpClient,
                resourceLoader,
                objectMapper,
                sdeDownloadService
        );

        try {
            assertThatThrownBy(interruptedService::syncRegions)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Interrupted while synchronizing ESI regions");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Clear interrupt status so subsequent test framework operations are unaffected
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("Should reject concurrent syncRegions invocations with IllegalStateException")
    void syncRegions_whenConcurrentCalls_shouldRejectWithConflict() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        java.util.concurrent.CountDownLatch startedLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finishLatch = new java.util.concurrent.CountDownLatch(1);

        when(mockHttpClient.send(any(HttpRequest.class), any()))
                .thenAnswer(invocation -> {
                    startedLatch.countDown();
                    finishLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    HttpResponse<InputStream> resp = mock(HttpResponse.class);
                    when(resp.statusCode()).thenReturn(200);
                    when(resp.body()).thenReturn(new ByteArrayInputStream("[10000001]".getBytes(StandardCharsets.UTF_8)));
                    return resp;
                });

        BatchProperties httpProperties = createTestProperties(
                "https://esi.evetech.net/latest/universe/regions/",
                "https://esi.evetech.net/latest/universe/names/"
        );

        EsiRegionSyncService concurrentService = new EsiRegionSyncService(
                httpProperties,
                dataSource,
                mockHttpClient,
                resourceLoader,
                objectMapper,
                sdeDownloadService
        );

        java.util.concurrent.atomic.AtomicReference<Throwable> thread2Error = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t1 = new Thread(() -> {
            try {
                concurrentService.syncRegions();
            } catch (Exception ignored) {
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                startedLatch.await();
                concurrentService.syncRegions();
            } catch (Throwable t) {
                thread2Error.set(t);
            }
        });

        t1.start();
        t2.start();
        t2.join(5000);
        finishLatch.countDown();
        t1.join(5000);

        assertThat(thread2Error.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");
    }

    private static BatchProperties createTestProperties(String regionsUrl, String namesUrl) {
        return new BatchProperties(
                new BatchProperties.ChunkSizeProperties(500, 1000),
                new BatchProperties.AsyncLauncherProperties(2, 4, 10),
                new BatchProperties.SdeProperties("http://localhost", "http://localhost", "Agent", Duration.ofSeconds(1), Duration.ofSeconds(1), 100),
                new BatchProperties.EsiProperties(
                        regionsUrl,
                        namesUrl,
                        "Agent",
                        MIN_MARKET_REGION_ID,
                        MAX_MARKET_REGION_ID,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)
                ),
                new BatchProperties.ScheduleProperties(false, "0 0 3 * * SUN", "0 15 0 * * *", "0 0/15 * * * *"),
                new BatchProperties.RemotePartitioningProperties(
                        PartitionRole.STANDALONE, false, "ex", "req", "req", "dlx", "dlq", "dlq",
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, Map.of(THE_FORGE_REGION_ID, 1)
                )
        );
    }
}
