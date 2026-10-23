package com.github.serbentd.eve.batch.job.sde;

import com.github.serbentd.eve.batch.dto.SdeItemDto;
import com.github.serbentd.eve.batch.dto.SdeStationDto;
import com.github.serbentd.eve.batch.service.SdeDownloadResult;
import com.github.serbentd.eve.batch.service.SdeDownloadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.test.MetaDataInstanceFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SdeReadersTest {

    private static final long TRITANIUM_TYPE_ID = 34L;
    private static final long PYERITE_TYPE_ID = 35L;
    private static final long JITA_4_4_STATION_ID = 60003760L;
    private static final long THE_FORGE_REGION_ID = 10000002L;

    private static final String ITEMS_SOURCE = "ITEMS";
    private static final String STATIONS_SOURCE = "STATIONS";
    private static final String ITEMS_URL = "https://example.com/items.csv";
    private static final String STATIONS_URL = "https://example.com/stations.csv";

    private final SdeDownloadService downloadService;

    SdeReadersTest(@Mock SdeDownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @Test
    @DisplayName("SdeItemReader should stream and parse CSV lines into SdeItemDto")
    void itemReader_shouldStreamCsvLines() throws Exception {
        String csv = """
                typeID,typeName,groupID,marketGroupID,volume,portionSize,published
                34,Tritanium,18,1857,0.01,1,1
                35,Pyerite,18,1857,0.01,1,1
                """;
        stubModified(ITEMS_SOURCE, ITEMS_URL, csv, "\"item-etag\"");

        SdeItemReader reader = new SdeItemReader(downloadService, ITEMS_SOURCE, ITEMS_URL);
        reader.open(new ExecutionContext());

        assertThat(reader.isModified()).isTrue();
        assertThat(reader.getEtag()).isEqualTo("\"item-etag\"");

        SdeItemDto row1 = reader.read();
        assertThat(row1).isNotNull();
        assertThat(row1.typeId()).isEqualTo(TRITANIUM_TYPE_ID);
        assertThat(row1.typeName()).isEqualTo("Tritanium");
        assertThat(row1.isPublished()).isTrue();

        SdeItemDto row2 = reader.read();
        assertThat(row2).isNotNull();
        assertThat(row2.typeId()).isEqualTo(PYERITE_TYPE_ID);
        assertThat(row2.typeName()).isEqualTo("Pyerite");

        assertThat(reader.read()).isNull();
        reader.close();
    }

    @Test
    @DisplayName("SdeItemReader should join multi-line quoted fields into a single logical record")
    void itemReader_shouldParseMultiLineQuotedDescription() throws Exception {
        String csv = """
                typeID,typeName,description,published
                34,Tritanium,"The main building block in space structures.
                A very hard, yet bendable metal.",1
                35,Pyerite,"Single-line description",1
                """;
        stubModified(ITEMS_SOURCE, ITEMS_URL, csv, null);

        SdeItemReader reader = new SdeItemReader(downloadService, ITEMS_SOURCE, ITEMS_URL);
        reader.open(new ExecutionContext());

        SdeItemDto row1 = reader.read();
        assertThat(row1).isNotNull();
        assertThat(row1.typeId()).isEqualTo(TRITANIUM_TYPE_ID);
        assertThat(row1.isPublished()).isTrue();

        SdeItemDto row2 = reader.read();
        assertThat(row2).isNotNull();
        assertThat(row2.typeId()).isEqualTo(PYERITE_TYPE_ID);

        assertThat(reader.read()).isNull();
        reader.close();
    }

    @Test
    @DisplayName("SdeItemReader should fail fast when the stream ends inside an unterminated quoted field")
    void itemReader_shouldFailOnUnterminatedQuote() throws Exception {
        String csv = """
                typeID,typeName,description
                34,Tritanium,"Unterminated description
                """;
        stubModified(ITEMS_SOURCE, ITEMS_URL, csv, null);

        SdeItemReader reader = new SdeItemReader(downloadService, ITEMS_SOURCE, ITEMS_URL);
        reader.open(new ExecutionContext());

        assertThatThrownBy(reader::read).isInstanceOf(FlatFileParseException.class);
        reader.close();
    }

    @Test
    @DisplayName("SdeItemReader should skip blank lines and rows missing a typeId")
    void itemReader_shouldSkipBlankAndInvalidRows() throws Exception {
        String csv = """
                typeID,typeName

                not-a-number,Broken
                34,Tritanium
                """;
        stubModified(ITEMS_SOURCE, ITEMS_URL, csv, null);

        SdeItemReader reader = new SdeItemReader(downloadService, ITEMS_SOURCE, ITEMS_URL);
        reader.open(new ExecutionContext());

        SdeItemDto row = reader.read();
        assertThat(row).isNotNull();
        assertThat(row.typeId()).isEqualTo(TRITANIUM_TYPE_ID);
        assertThat(reader.read()).isNull();
        reader.close();
    }

    @Test
    @DisplayName("SdeItemReader should return null immediately when 304 NotModified")
    void itemReader_shouldHandleNotModified() throws Exception {
        when(downloadService.downloadStream(ITEMS_SOURCE, ITEMS_URL))
                .thenReturn(new SdeDownloadResult.NotModified("\"cached-etag\"", "cached-date"));

        SdeItemReader reader = new SdeItemReader(downloadService, ITEMS_SOURCE, ITEMS_URL);
        reader.open(new ExecutionContext());

        assertThat(reader.isModified()).isFalse();
        assertThat(reader.read()).isNull();
        reader.close();
    }

    @Test
    @DisplayName("SdeStationReader should stream and parse CSV lines into SdeStationDto")
    void stationReader_shouldStreamCsvLines() throws Exception {
        String csv = """
                stationID,stationName,solarSystemID,solarSystemName,regionID,security,is_structure
                60003760,Jita IV - Moon 4,30000142,Jita,10000002,0.95,false
                """;
        stubModified(STATIONS_SOURCE, STATIONS_URL, csv, "\"station-etag\"");

        SdeStationReader reader = new SdeStationReader(downloadService, STATIONS_SOURCE, STATIONS_URL);
        reader.open(new ExecutionContext());

        assertThat(reader.isModified()).isTrue();
        SdeStationDto station = reader.read();
        assertThat(station).isNotNull();
        assertThat(station.stationId()).isEqualTo(JITA_4_4_STATION_ID);
        assertThat(station.stationName()).isEqualTo("Jita IV - Moon 4");
        assertThat(station.regionId()).isEqualTo(THE_FORGE_REGION_ID);
        assertThat(station.securityStatus()).isEqualByComparingTo("0.95");
        assertThat(station.isStructure()).isFalse();

        assertThat(reader.read()).isNull();
        reader.close();
    }

    @Test
    @DisplayName("SdeStationReader should return null immediately when 304 NotModified")
    void stationReader_shouldHandleNotModified() throws Exception {
        when(downloadService.downloadStream(STATIONS_SOURCE, STATIONS_URL))
                .thenReturn(new SdeDownloadResult.NotModified("\"station-etag\"", "cached-date"));

        SdeStationReader reader = new SdeStationReader(downloadService, STATIONS_SOURCE, STATIONS_URL);
        reader.open(new ExecutionContext());

        assertThat(reader.isModified()).isFalse();
        assertThat(reader.read()).isNull();
        reader.close();
    }

    @Test
    @DisplayName("SdeItemReader should re-interrupt thread and wrap in ItemStreamException when interrupted")
    void itemReader_shouldHandleInterruptedException() throws Exception {
        when(downloadService.downloadStream(ITEMS_SOURCE, ITEMS_URL))
                .thenThrow(new InterruptedException("Simulation"));

        SdeItemReader reader = new SdeItemReader(downloadService, ITEMS_SOURCE, ITEMS_URL);
        ExecutionContext ec = new ExecutionContext();

        assertThatThrownBy(() -> reader.open(ec))
                .isInstanceOf(ItemStreamException.class)
                .hasMessageContaining("Interrupted while opening SDE stream")
                .hasCauseInstanceOf(InterruptedException.class);

        // Verify thread interrupt flag was preserved by re-interrupt
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    @DisplayName("SdeStationReader should re-interrupt thread and wrap in ItemStreamException when interrupted")
    void stationReader_shouldHandleInterruptedException() throws Exception {
        when(downloadService.downloadStream(STATIONS_SOURCE, STATIONS_URL))
                .thenThrow(new InterruptedException("Simulation"));

        SdeStationReader reader = new SdeStationReader(downloadService, STATIONS_SOURCE, STATIONS_URL);
        ExecutionContext ec = new ExecutionContext();

        assertThatThrownBy(() -> reader.open(ec))
                .isInstanceOf(ItemStreamException.class)
                .hasMessageContaining("Interrupted while opening SDE stream")
                .hasCauseInstanceOf(InterruptedException.class);

        // Verify thread interrupt flag was preserved by re-interrupt
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    @DisplayName("afterStep should record SUCCESS with the step write count when a modified feed completes")
    void afterStep_whenCompletedAndModified_shouldRecordSuccess() throws Exception {
        stubModified(ITEMS_SOURCE, ITEMS_URL, "typeID\n34\n", "\"item-etag\"");
        SdeItemReader reader = new SdeItemReader(downloadService, ITEMS_SOURCE, ITEMS_URL);
        reader.open(new ExecutionContext());

        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
        stepExecution.setStatus(BatchStatus.COMPLETED);
        stepExecution.setWriteCount(1);

        reader.afterStep(stepExecution);
        reader.close();

        verify(downloadService).recordSuccessfulSync(
                eq(ITEMS_SOURCE), eq(ITEMS_URL), eq("\"item-etag\""), any(), eq(1), eq(0), eq(1), any(Instant.class));
        verify(downloadService, never()).recordFailedSync(anyString(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    @DisplayName("afterStep should record SKIPPED_NOT_MODIFIED when the feed was not modified (304)")
    void afterStep_whenNotModified_shouldRecordSkippedSync() throws Exception {
        when(downloadService.downloadStream(ITEMS_SOURCE, ITEMS_URL))
                .thenReturn(new SdeDownloadResult.NotModified("\"cached-etag\"", "cached-date"));
        SdeItemReader reader = new SdeItemReader(downloadService, ITEMS_SOURCE, ITEMS_URL);
        reader.open(new ExecutionContext());

        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
        stepExecution.setStatus(BatchStatus.COMPLETED);

        reader.afterStep(stepExecution);

        verify(downloadService).recordSkippedSync(
                eq(ITEMS_SOURCE), eq(ITEMS_URL), eq("\"cached-etag\""), eq("cached-date"), any(Instant.class));
        verify(downloadService, never()).recordSuccessfulSync(
                anyString(), anyString(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        verify(downloadService, never()).recordFailedSync(anyString(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    @DisplayName("afterStep should record FAILED with root-cause diagnostics when the step fails")
    void afterStep_whenFailed_shouldRecordFailureWithRootCause() throws Exception {
        when(downloadService.downloadStream(STATIONS_SOURCE, STATIONS_URL))
                .thenThrow(new IOException("HTTP 503 error downloading SDE"));
        SdeStationReader reader = new SdeStationReader(downloadService, STATIONS_SOURCE, STATIONS_URL);
        ExecutionContext ec = new ExecutionContext();

        ItemStreamException openFailure = assertThrows(ItemStreamException.class, () -> reader.open(ec));

        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
        stepExecution.setStatus(BatchStatus.FAILED);
        stepExecution.addFailureException(openFailure);

        reader.afterStep(stepExecution);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(downloadService).recordFailedSync(
                eq(STATIONS_SOURCE), eq(STATIONS_URL), eq(0), messageCaptor.capture(), any(Instant.class));
        assertThat(messageCaptor.getValue())
                .contains("Failed to open SDE stream for STATIONS")
                .contains("root cause")
                .contains("HTTP 503");
    }

    private void stubModified(String source, String url, String csv, String etag) throws Exception {
        when(downloadService.downloadStream(source, url))
                .thenReturn(new SdeDownloadResult.Modified(
                        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                        etag,
                        "Wed, 21 Oct 2026 12:00:00 GMT"
                ));
    }
}
