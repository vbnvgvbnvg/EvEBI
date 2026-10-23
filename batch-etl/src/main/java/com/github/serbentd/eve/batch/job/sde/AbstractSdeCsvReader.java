package com.github.serbentd.eve.batch.job.sde;

import com.github.serbentd.eve.batch.service.SdeDownloadResult;
import com.github.serbentd.eve.batch.service.SdeDownloadService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.separator.DefaultRecordSeparatorPolicy;
import org.springframework.batch.item.file.separator.RecordSeparatorPolicy;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.core.NestedExceptionUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Base streaming CSV reader for SDE feeds delivered via conditional HTTP streams or local resources.
 * <p>
 * Handles stream lifecycle, header-name resolution (case- and underscore-insensitive), RFC 4180
 * multi-line quoted records, typed token parsing, and terminal {@code sde_metadata} audit logging.
 * Subclasses only map a tokenized record to their transit DTO.
 *
 * @param <T> immutable transit record type produced per CSV row
 */
@Slf4j
abstract class AbstractSdeCsvReader<T> implements ItemStreamReader<T>, StepExecutionListener {

    private final SdeDownloadService downloadService;
    private final String syncSource;
    private final String url;
    private final DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
    private final RecordSeparatorPolicy separatorPolicy = new DefaultRecordSeparatorPolicy();
    private final Map<String, Integer> headerMap = new HashMap<>();

    private BufferedReader reader;
    private SdeDownloadResult downloadResult;
    private boolean isExhausted = false;
    @Getter
    private String etag;
    @Getter
    private String lastModified;
    private Instant startedAt;

    protected AbstractSdeCsvReader(SdeDownloadService downloadService, String syncSource, String url) {
        this.downloadService = downloadService;
        this.syncSource = syncSource;
        this.url = url;
    }

    /**
     * Maps one complete, tokenized CSV record to a transit DTO.
     *
     * @return the mapped record, or {@code null} to skip rows missing mandatory identifiers
     */
    protected abstract T mapRecord(String[] tokens);

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        try {
            this.startedAt = Instant.now();
            this.downloadResult = downloadService.downloadStream(syncSource, url);
            switch (downloadResult) {
                case SdeDownloadResult.NotModified notModified -> {
                    log.info("SDE feed '{}' not modified (304), skipping streaming", syncSource);
                    this.isExhausted = true;
                    this.etag = notModified.etag();
                    this.lastModified = notModified.lastModified();
                }
                case SdeDownloadResult.Modified modified -> {
                    this.etag = modified.etag();
                    this.lastModified = modified.lastModified();
                    this.reader = new BufferedReader(new InputStreamReader(modified.inputStream(), StandardCharsets.UTF_8));
                    String headerLine = this.reader.readLine();
                    if (headerLine != null) {
                        String[] headers = tokenizer.tokenize(headerLine).getValues();
                        for (int i = 0; i < headers.length; i++) {
                            headerMap.put(normalizeHeader(headers[i]), i);
                        }
                    } else {
                        this.isExhausted = true;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ItemStreamException("Interrupted while opening SDE stream for " + syncSource, e);
        } catch (Exception e) {
            throw new ItemStreamException("Failed to open SDE stream for " + syncSource, e);
        }
    }

    @Override
    public T read() throws Exception {
        if (isExhausted || reader == null) {
            return null;
        }

        String record;
        while ((record = readLogicalRecord()) != null) {
            if (record.isBlank()) {
                continue;
            }
            T item = mapRecord(tokenizer.tokenize(record).getValues());
            if (item != null) {
                return item;
            }
        }

        this.isExhausted = true;
        return null;
    }

    @Override
    public void update(ExecutionContext executionContext) {
        // Stateless streaming reader; no persistent cursor needed across chunk bounds
    }

    @Override
    public void close() throws ItemStreamException {
        try {
            if (reader != null) {
                reader.close();
            }
            if (downloadResult instanceof SdeDownloadResult.Modified modified) {
                modified.close();
            }
        } catch (IOException e) {
            log.warn("Error closing SDE reader for {}", syncSource, e);
        }
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        int writeCount = Math.toIntExact(stepExecution.getWriteCount());
        Instant syncStart = startedAt != null ? startedAt : Instant.now();

        if (stepExecution.getStatus() == BatchStatus.COMPLETED) {
            if (downloadResult instanceof SdeDownloadResult.Modified) {
                downloadService.recordSuccessfulSync(
                        syncSource, url, etag, lastModified, writeCount, 0, writeCount, syncStart);
            } else if (downloadResult instanceof SdeDownloadResult.NotModified) {
                downloadService.recordSkippedSync(
                        syncSource, url, etag, lastModified, syncStart);
            }
        } else if (stepExecution.getStatus() == BatchStatus.FAILED) {
            downloadService.recordFailedSync(
                    syncSource, url, writeCount, summarizeFailures(stepExecution), syncStart);
        }
        return stepExecution.getExitStatus();
    }

    public boolean isModified() {
        return downloadResult instanceof SdeDownloadResult.Modified;
    }

    /**
     * Reads one logical CSV record, joining physical lines while a quoted field remains open
     * (e.g. multi-line item descriptions in {@code invTypes.csv}).
     */
    private String readLogicalRecord() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            return null;
        }
        String record = line;
        while (!separatorPolicy.isEndOfRecord(record)) {
            line = reader.readLine();
            if (line == null) {
                throw new FlatFileParseException("Unexpected end of SDE stream before record complete", record);
            }
            record = separatorPolicy.preProcess(record) + line;
        }
        return separatorPolicy.postProcess(record);
    }

    private static String summarizeFailures(StepExecution stepExecution) {
        return stepExecution.getFailureExceptions().stream()
                .map(failure -> {
                    Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
                    return root == failure
                            ? String.valueOf(failure)
                            : failure + " (root cause: " + root + ")";
                })
                .collect(Collectors.joining("; "));
    }

    private static String normalizeHeader(String header) {
        if (header == null) {
            return "";
        }
        return header.trim().toLowerCase(Locale.ROOT).replace("_", "");
    }

    protected String getString(String[] tokens, String... candidates) {
        for (String candidate : candidates) {
            Integer idx = headerMap.get(candidate);
            if (idx != null && idx < tokens.length) {
                String val = tokens[idx].trim();
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    protected Long getLong(String[] tokens, String... candidates) {
        String val = getString(tokens, candidates);
        if (val == null) {
            return null;
        }
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected Integer getInteger(String[] tokens, String... candidates) {
        String val = getString(tokens, candidates);
        if (val == null) {
            return null;
        }
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected BigDecimal getBigDecimal(String[] tokens, String... candidates) {
        String val = getString(tokens, candidates);
        if (val == null) {
            return null;
        }
        try {
            return new BigDecimal(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected Boolean getBoolean(String[] tokens, String... candidates) {
        String val = getString(tokens, candidates);
        if (val == null) {
            return null;
        }
        return "1".equals(val) || "true".equalsIgnoreCase(val) || "t".equalsIgnoreCase(val);
    }
}
