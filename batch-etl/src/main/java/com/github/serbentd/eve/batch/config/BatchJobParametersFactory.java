package com.github.serbentd.eve.batch.config;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.stereotype.Component;

/**
 * Centralized factory for creating {@link JobParameters} enriched with Kubernetes Downward API
 * metadata ({@code podName}, {@code podNodeName}) as non-identifying execution parameters.
 */
@Component
@RequiredArgsConstructor
public class BatchJobParametersFactory {

    public static final String POD_NAME_PARAM = "podName";
    public static final String POD_NODE_NAME_PARAM = "podNodeName";
    public static final String TIMESTAMP_PARAM = "timestamp";

    private final BatchProperties batchProperties;

    /**
     * Initializes a new {@link JobParametersBuilder} pre-populated with cluster audit metadata
     * ({@code podName}, {@code podNodeName}) flagged as non-identifying.
     *
     * @return builder initialized with non-identifying Kubernetes metadata
     */
    public JobParametersBuilder newBuilder() {
        JobParametersBuilder builder = new JobParametersBuilder();
        enrichWithKubernetesMetadata(builder);
        return builder;
    }

    /**
     * Enriches an existing {@link JobParametersBuilder} with non-identifying Kubernetes metadata
     * ({@code podName}, {@code podNodeName}).
     *
     * @param builder the builder to enrich
     * @return the same builder instance enriched with cluster metadata
     */
    public JobParametersBuilder enrich(JobParametersBuilder builder) {
        enrichWithKubernetesMetadata(builder);
        return builder;
    }

    /**
     * Enriches an existing {@link JobParameters} instance with non-identifying Kubernetes metadata.
     *
     * @param source the source job parameters
     * @return new {@link JobParameters} instance containing source parameters plus cluster metadata
     */
    public JobParameters enrich(JobParameters source) {
        JobParametersBuilder builder = new JobParametersBuilder(source);
        enrichWithKubernetesMetadata(builder);
        return builder.toJobParameters();
    }

    private void enrichWithKubernetesMetadata(JobParametersBuilder builder) {
        String podName = batchProperties.kubernetes().podName();
        String podNodeName = batchProperties.kubernetes().podNodeName();

        builder.addString(POD_NAME_PARAM, podName, false);
        builder.addString(POD_NODE_NAME_PARAM, podNodeName, false);
    }
}
