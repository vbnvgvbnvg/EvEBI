package com.github.serbentd.eve.batch.k8s;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Kubernetes Manifest & Container Entrypoint Validation")
class KubernetesManifestValidationTest {

    private static final String CRONJOBS_YAML = "infrastructure/k8s/batch-etl-cronjobs.yaml";
    private static final String WORKERS_YAML = "infrastructure/k8s/batch-etl-workers.yaml";
    private static final String DOCKERFILE = "batch-etl/Dockerfile";

    @Test
    @DisplayName("Should validate CronJob specifications enforce Forbid concurrency, OnFailure restart, 120s grace period, and Downward API")
    void shouldValidateCronJobManifestInvariants() throws Exception {
        Path path = resolvePath(CRONJOBS_YAML);
        Yaml yaml = new Yaml();

        List<Map<String, Object>> documents = new ArrayList<>();
        try (InputStream is = Files.newInputStream(path)) {
            for (Object doc : yaml.loadAll(is)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) doc;
                documents.add(map);
            }
        }

        assertThat(documents).hasSize(3);

        for (Map<String, Object> doc : documents) {
            assertThat(doc.get("kind")).isEqualTo("CronJob");

            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
            String cronJobName = (String) metadata.get("name");
            assertThat(cronJobName).isNotBlank();

            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
            assertThat(spec.get("concurrencyPolicy"))
                    .withFailMessage("CronJob %s must have concurrencyPolicy: Forbid", cronJobName)
                    .isEqualTo("Forbid");

            @SuppressWarnings("unchecked")
            Map<String, Object> jobTemplate = (Map<String, Object>) spec.get("jobTemplate");
            @SuppressWarnings("unchecked")
            Map<String, Object> jobSpec = (Map<String, Object>) jobTemplate.get("spec");
            @SuppressWarnings("unchecked")
            Map<String, Object> template = (Map<String, Object>) jobSpec.get("template");
            @SuppressWarnings("unchecked")
            Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");

            assertThat(podSpec.get("restartPolicy"))
                    .withFailMessage("CronJob %s pod template must have restartPolicy: OnFailure", cronJobName)
                    .isEqualTo("OnFailure");

            assertThat(podSpec.get("terminationGracePeriodSeconds"))
                    .withFailMessage("CronJob %s pod template must have terminationGracePeriodSeconds: 120", cronJobName)
                    .isEqualTo(120);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> containers = (List<Map<String, Object>>) podSpec.get("containers");
            assertThat(containers).isNotEmpty();

            Map<String, Object> container = containers.get(0);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> envList = (List<Map<String, Object>>) container.get("env");
            assertThat(envList).isNotNull();

            assertDownwardApiEnvPresent(envList, "POD_NAME", "metadata.name");
            assertDownwardApiEnvPresent(envList, "POD_NODE_NAME", "spec.nodeName");

            String mode = getEnvValue(envList, "EVE_BATCH_EXECUTION_MODE");
            assertThat(mode).isIn("SDE_SYNC", "DAILY_MARKET_SUMMARY", "ORDER_BOOK_LIQUIDITY");

            if ("SDE_SYNC".equals(mode)) {
                assertThat(getEnvValue(envList, "EVE_BATCH_PARTITION_ROLE")).isEqualTo("STANDALONE");
            } else {
                assertThat(getEnvValue(envList, "EVE_BATCH_PARTITION_ROLE")).isEqualTo("MANAGER");
                assertThat(getEnvValue(envList, "EVE_BATCH_RABBIT_HEALTH_ENABLED")).isEqualTo("true");
            }
        }
    }

    @Test
    @DisplayName("Should validate Worker Deployment enforces 120s grace period, Downward API, and probes")
    void shouldValidateWorkerDeploymentInvariants() throws Exception {
        Path path = resolvePath(WORKERS_YAML);
        Yaml yaml = new Yaml();

        Map<String, Object> doc;
        try (InputStream is = Files.newInputStream(path)) {
            doc = yaml.load(is);
        }

        assertThat(doc.get("kind")).isEqualTo("Deployment");

        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) spec.get("template");
        @SuppressWarnings("unchecked")
        Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");

        assertThat(podSpec.get("terminationGracePeriodSeconds")).isEqualTo(120);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> containers = (List<Map<String, Object>>) podSpec.get("containers");
        assertThat(containers).isNotEmpty();

        Map<String, Object> container = containers.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> envList = (List<Map<String, Object>>) container.get("env");
        assertThat(envList).isNotNull();

        assertDownwardApiEnvPresent(envList, "POD_NAME", "metadata.name");
        assertDownwardApiEnvPresent(envList, "POD_NODE_NAME", "spec.nodeName");

        assertThat(getEnvValue(envList, "EVE_BATCH_PARTITION_ROLE")).isEqualTo("WORKER");
        assertThat(getEnvValue(envList, "EVE_BATCH_EXECUTION_MODE")).isEqualTo("DAEMON");
        assertThat(getEnvValue(envList, "EVE_BATCH_RABBIT_HEALTH_ENABLED")).isEqualTo("true");

        @SuppressWarnings("unchecked")
        Map<String, Object> livenessProbe = (Map<String, Object>) container.get("livenessProbe");
        assertThat(livenessProbe).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> livenessHttpGet = (Map<String, Object>) livenessProbe.get("httpGet");
        assertThat(livenessHttpGet.get("path")).isEqualTo("/actuator/health/liveness");

        @SuppressWarnings("unchecked")
        Map<String, Object> readinessProbe = (Map<String, Object>) container.get("readinessProbe");
        assertThat(readinessProbe).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> readinessHttpGet = (Map<String, Object>) readinessProbe.get("httpGet");
        assertThat(readinessHttpGet.get("path")).isEqualTo("/actuator/health/readiness");
    }

    @Test
    @DisplayName("Should validate Dockerfile contains exec java PID 1 entrypoint and non-root user")
    void shouldValidateDockerfileDirectives() throws Exception {
        Path path = resolvePath(DOCKERFILE);
        String dockerfileContent = Files.readString(path);

        assertThat(dockerfileContent)
                .withFailMessage("Dockerfile must use 'exec java' in entrypoint to ensure PID 1 signal forwarding")
                .contains("exec java");

        assertThat(dockerfileContent)
                .withFailMessage("Dockerfile must run as non-root user")
                .contains("USER 10001:10001");
    }

    private void assertDownwardApiEnvPresent(List<Map<String, Object>> envList, String envName, String expectedFieldPath) {
        boolean found = false;
        for (Map<String, Object> env : envList) {
            if (envName.equals(env.get("name"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> valueFrom = (Map<String, Object>) env.get("valueFrom");
                assertThat(valueFrom).isNotNull();
                @SuppressWarnings("unchecked")
                Map<String, Object> fieldRef = (Map<String, Object>) valueFrom.get("fieldRef");
                assertThat(fieldRef).isNotNull();
                assertThat(fieldRef.get("fieldPath")).isEqualTo(expectedFieldPath);
                found = true;
                break;
            }
        }
        assertThat(found)
                .withFailMessage("Environment variable %s with fieldPath %s must be present", envName, expectedFieldPath)
                .isTrue();
    }

    private String getEnvValue(List<Map<String, Object>> envList, String envName) {
        for (Map<String, Object> env : envList) {
            if (envName.equals(env.get("name"))) {
                return (String) env.get("value");
            }
        }
        return null;
    }

    private Path resolvePath(String relativePath) {
        Path direct = Path.of(relativePath);
        if (Files.exists(direct)) {
            return direct;
        }
        Path parent = Path.of("..", relativePath);
        if (Files.exists(parent)) {
            return parent;
        }
        throw new IllegalArgumentException("Unable to resolve path: " + relativePath);
    }
}
