package com.github.serbentd.eve.batch.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.DefaultJobKeyGenerator;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BatchJobParametersFactoryTest {

    @Test
    @DisplayName("newBuilder should inject podName and podNodeName as non-identifying parameters")
    void newBuilder_shouldInjectNonIdentifyingMetadata() {
        BatchProperties properties = mock(BatchProperties.class);
        when(properties.kubernetes()).thenReturn(new BatchProperties.KubernetesProperties(
                "batch-etl-pod-42", "k8s-worker-node-7", BatchExecutionMode.DAEMON, false));

        BatchJobParametersFactory factory = new BatchJobParametersFactory(properties);
        JobParameters parameters = factory.newBuilder().toJobParameters();

        assertThat(parameters.getString(BatchJobParametersFactory.POD_NAME_PARAM)).isEqualTo("batch-etl-pod-42");
        assertThat(parameters.getString(BatchJobParametersFactory.POD_NODE_NAME_PARAM)).isEqualTo("k8s-worker-node-7");

        assertThat(parameters.getParameters().get(BatchJobParametersFactory.POD_NAME_PARAM).isIdentifying()).isFalse();
        assertThat(parameters.getParameters().get(BatchJobParametersFactory.POD_NODE_NAME_PARAM).isIdentifying()).isFalse();
    }

    @Test
    @DisplayName("enrich builder should preserve existing parameters and add non-identifying cluster metadata")
    void enrichBuilder_shouldPreserveExistingAndAddMetadata() {
        BatchProperties properties = mock(BatchProperties.class);
        when(properties.kubernetes()).thenReturn(new BatchProperties.KubernetesProperties(
                "batch-etl-pod-99", "k8s-node-1", BatchExecutionMode.DAILY_MARKET_SUMMARY, true));

        BatchJobParametersFactory factory = new BatchJobParametersFactory(properties);
        JobParametersBuilder builder = new JobParametersBuilder()
                .addString("targetDate", "2026-11-12");

        JobParameters parameters = factory.enrich(builder).toJobParameters();

        assertThat(parameters.getString("targetDate")).isEqualTo("2026-11-12");
        assertThat(parameters.getParameters().get("targetDate").isIdentifying()).isTrue();

        assertThat(parameters.getString(BatchJobParametersFactory.POD_NAME_PARAM)).isEqualTo("batch-etl-pod-99");
        assertThat(parameters.getString(BatchJobParametersFactory.POD_NODE_NAME_PARAM)).isEqualTo("k8s-node-1");
        assertThat(parameters.getParameters().get(BatchJobParametersFactory.POD_NAME_PARAM).isIdentifying()).isFalse();
        assertThat(parameters.getParameters().get(BatchJobParametersFactory.POD_NODE_NAME_PARAM).isIdentifying()).isFalse();
    }

    @Test
    @DisplayName("enrich JobParameters should produce copy containing non-identifying cluster metadata")
    void enrichJobParameters_shouldAddClusterMetadata() {
        BatchProperties properties = mock(BatchProperties.class);
        when(properties.kubernetes()).thenReturn(new BatchProperties.KubernetesProperties(
                "pod-alpha", "node-beta", BatchExecutionMode.ORDER_BOOK_LIQUIDITY, true));

        BatchJobParametersFactory factory = new BatchJobParametersFactory(properties);
        JobParameters source = new JobParametersBuilder()
                .addString("snapshotTime", "2026-11-12T12:00:00Z")
                .toJobParameters();

        JobParameters enriched = factory.enrich(source);

        assertThat(enriched.getString("snapshotTime")).isEqualTo("2026-11-12T12:00:00Z");
        assertThat(enriched.getString(BatchJobParametersFactory.POD_NAME_PARAM)).isEqualTo("pod-alpha");
        assertThat(enriched.getString(BatchJobParametersFactory.POD_NODE_NAME_PARAM)).isEqualTo("node-beta");
        assertThat(enriched.getParameters().get(BatchJobParametersFactory.POD_NAME_PARAM).isIdentifying()).isFalse();
        assertThat(enriched.getParameters().get(BatchJobParametersFactory.POD_NODE_NAME_PARAM).isIdentifying()).isFalse();
    }

    @Test
    @DisplayName("Non-identifying metadata should preserve JobInstance identity equality across rescheduled pods")
    void nonIdentifyingMetadata_shouldPreserveJobInstanceEquality() {
        BatchProperties propertiesPod1 = mock(BatchProperties.class);
        when(propertiesPod1.kubernetes()).thenReturn(new BatchProperties.KubernetesProperties(
                "batch-pod-failed-1", "node-evicted-1", BatchExecutionMode.DAILY_MARKET_SUMMARY, true));

        BatchProperties propertiesPod2 = mock(BatchProperties.class);
        when(propertiesPod2.kubernetes()).thenReturn(new BatchProperties.KubernetesProperties(
                "batch-pod-restarted-2", "node-healthy-2", BatchExecutionMode.DAILY_MARKET_SUMMARY, true));

        BatchJobParametersFactory factory1 = new BatchJobParametersFactory(propertiesPod1);
        BatchJobParametersFactory factory2 = new BatchJobParametersFactory(propertiesPod2);

        JobParameters paramsFromPod1 = factory1.newBuilder()
                .addString("targetDate", "2026-11-12")
                .toJobParameters();

        JobParameters paramsFromPod2 = factory2.newBuilder()
                .addString("targetDate", "2026-11-12")
                .toJobParameters();

        // Spring Batch generates JOB_KEY using DefaultJobKeyGenerator which strictly inspects identifying parameters.
        // This ensures the rescheduled pod resolves the exact same logical JobInstance.
        DefaultJobKeyGenerator keyGenerator = new DefaultJobKeyGenerator();
        assertThat(keyGenerator.generateKey(paramsFromPod1)).isEqualTo(keyGenerator.generateKey(paramsFromPod2));

        // But each execution retains its distinct executing pod audit trail
        assertThat(paramsFromPod1.getString(BatchJobParametersFactory.POD_NAME_PARAM)).isEqualTo("batch-pod-failed-1");
        assertThat(paramsFromPod2.getString(BatchJobParametersFactory.POD_NAME_PARAM)).isEqualTo("batch-pod-restarted-2");
    }
}
