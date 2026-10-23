package com.github.serbentd.eve.batch.service;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Sealed result type representing the outcome of a conditional SDE download request.
 */
public sealed interface SdeDownloadResult {

    /**
     * Represents a remote payload that has been downloaded (HTTP 200) or loaded from a resource.
     *
     * @param inputStream  streaming input channel (transparently decompressed if gzipped)
     * @param etag         new HTTP ETag cache validator
     * @param lastModified new HTTP Last-Modified timestamp string
     */
    record Modified(
            InputStream inputStream,
            String etag,
            String lastModified
    ) implements SdeDownloadResult, Closeable {

        @Override
        public void close() throws IOException {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

    /**
     * Represents a conditional HTTP 304 Not Modified response, indicating no changes since last sync.
     *
     * @param etag         persisted cache validator
     * @param lastModified persisted Last-Modified timestamp
     */
    record NotModified(
            String etag,
            String lastModified
    ) implements SdeDownloadResult {}
}
