package com.github.serbentd.eve.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.yaml.snakeyaml.Yaml;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Supply Chain & Secret Externalization Audit")
class SecretExternalizationAuditTest {

    private static final List<String> PROD_CONFIG_PATHS = List.of(
            "ingestion-poller/src/main/resources/application-prod.yml",
            "worker-service/src/main/resources/application-prod.yml",
            "batch-etl/src/main/resources/application-prod.yml"
    );

    private static final List<String> FORBIDDEN_FALLBACK_SUFFIXES = List.of(
            ":eve_password}",
            ":eve_local_api_key}",
            ":eve_local_actuator_key}",
            ":eve_local_operator_key}"
    );

    private static final Set<String> SENSITIVE_K8S_ENV_VARS = Set.of(
            "SPRING_DATASOURCE_PASSWORD",
            "SPRING_LIQUIBASE_PASSWORD",
            "SPRING_RABBITMQ_PASSWORD",
            "EVE_SECURITY_PASSWORD",
            "EVE_SECURITY_API_KEYS",
            "EVE_SECURITY_ACTUATOR_API_KEYS",
            "EVE_SECURITY_OPERATOR_API_KEYS"
    );

    @Test
    @DisplayName("Production application-prod.yml files must not define local fallback credentials and must enforce TLS")
    void productionConfigs_mustExternalizeSecretsAndEnforceTls() throws Exception {
        Yaml yaml = new Yaml();

        for (String relativePath : PROD_CONFIG_PATHS) {
            Path path = resolveRepoPath(relativePath);
            String rawContent = Files.readString(path);

            for (String forbiddenSuffix : FORBIDDEN_FALLBACK_SUFFIXES) {
                assertThat(rawContent)
                        .withFailMessage("%s must not contain local fallback suffix '%s'", relativePath, forbiddenSuffix)
                        .doesNotContain(forbiddenSuffix);
            }

            Map<String, Object> parsed;
            try (InputStream is = Files.newInputStream(path)) {
                parsed = yaml.load(is);
            }
            assertThat(parsed).isNotNull();

            @SuppressWarnings("unchecked")
            Map<String, Object> spring = (Map<String, Object>) parsed.get("spring");
            assertThat(spring).isNotNull();

            @SuppressWarnings("unchecked")
            Map<String, Object> rabbitmq = (Map<String, Object>) spring.get("rabbitmq");
            assertThat(rabbitmq).isNotNull();
            assertEnvVarOnlyPlaceholder(relativePath, "spring.rabbitmq.password", rabbitmq.get("password"));

            @SuppressWarnings("unchecked")
            Map<String, Object> rabbitSsl = (Map<String, Object>) rabbitmq.get("ssl");
            assertThat(rabbitSsl).isNotNull();
            assertThat(String.valueOf(rabbitSsl.get("enabled"))).contains(":true");

            if (spring.containsKey("datasource")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
                assertThat(String.valueOf(datasource.get("url"))).contains("sslmode=${POSTGRES_SSL_MODE:require}");
                assertEnvVarOnlyPlaceholder(relativePath, "spring.datasource.password", datasource.get("password"));

                @SuppressWarnings("unchecked")
                Map<String, Object> liquibase = (Map<String, Object>) spring.get("liquibase");
                assertThat(liquibase).isNotNull();
                assertEnvVarOnlyPlaceholder(relativePath, "spring.liquibase.password", liquibase.get("password"));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> eve = (Map<String, Object>) parsed.get("eve");
            @SuppressWarnings("unchecked")
            Map<String, Object> security = (Map<String, Object>) eve.get("security");
            assertEnvVarOnlyPlaceholder(relativePath, "eve.security.password", security.get("password"));
            assertEnvVarOnlyPlaceholder(relativePath, "eve.security.admin-api-keys", security.get("admin-api-keys"));
            assertEnvVarOnlyPlaceholder(relativePath, "eve.security.actuator-api-keys", security.get("actuator-api-keys"));
            if (security.containsKey("operator-api-keys")) {
                assertEnvVarOnlyPlaceholder(relativePath, "eve.security.operator-api-keys", security.get("operator-api-keys"));
            }
        }
    }

    @Test
    @DisplayName("Kubernetes manifests must inject sensitive credentials via secretKeyRef and template must hold placeholders only")
    void kubernetesManifests_mustNotContainInlineSecrets() throws Exception {
        Yaml yaml = new Yaml();

        for (String manifestPath : List.of(
                "infrastructure/k8s/ingestion-poller-deployment.yaml",
                "infrastructure/k8s/worker-service-deployment.yaml",
                "infrastructure/k8s/batch-etl-cronjobs.yaml",
                "infrastructure/k8s/batch-etl-workers.yaml")) {
            Path path = resolveRepoPath(manifestPath);
            try (InputStream is = Files.newInputStream(path)) {
                for (Object doc : yaml.loadAll(is)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) doc;
                    assertThat(map.get("kind")).isNotEqualTo("Secret");
                    assertNoInlineSensitiveEnvVars(manifestPath, map);
                }
            }
        }

        Path templatePath = resolveRepoPath("infrastructure/k8s/secrets.example.yaml");
        try (InputStream is = Files.newInputStream(templatePath)) {
            for (Object doc : yaml.loadAll(is)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) doc;
                assertThat(map.get("kind")).isEqualTo("Secret");
                @SuppressWarnings("unchecked")
                Map<String, String> stringData = (Map<String, String>) map.get("stringData");
                assertThat(stringData).isNotNull();
                stringData.forEach((key, value) -> {
                    assertThat(value).doesNotContain("eve_password");
                    if (key.contains("password") || key.contains("api-keys")) {
                        assertThat(value)
                                .withFailMessage("Secret template key %s must use a <REPLACE_WITH_...> placeholder", key)
                                .startsWith("<REPLACE_WITH_");
                    }
                });
            }
        }
    }

    @Test
    @DisplayName(".gitignore must exclude environment files, private keys, keystores, and concrete Kubernetes secrets")
    void gitignore_mustExcludeSecretsAndCertificates() throws Exception {
        String gitignore = Files.readString(resolveRepoPath(".gitignore"));
        List<String> lines = gitignore.lines().map(String::trim).toList();

        assertThat(lines).contains(
                ".env",
                "*.env",
                ".env.*",
                "!.env.example",
                "*.pem",
                "*.key",
                "*.p12",
                "*.jks",
                "infrastructure/k8s/*secret*.yaml",
                "!infrastructure/k8s/secrets.example.yaml",
                ".owasp-data/"
        );
    }

    @Test
    @DisplayName("Supply chain build controls (OWASP Dependency-Check, Maven Enforcer, Dependabot) must be configured")
    void supplyChainControls_mustBeConfiguredInBuildAndGithub() throws Exception {
        String rootPom = Files.readString(resolveRepoPath("pom.xml"));
        assertThat(rootPom)
                .contains("maven-enforcer-plugin")
                .contains("requireJavaVersion")
                .contains("requireMavenVersion")
                .contains("banDuplicatePomDependencyVersions")
                .contains("dependency-check-maven")
                .contains("<id>security-audit</id>")
                .contains("<failBuildOnCVSS>")
                .contains("<nvdApiKeyEnvironmentVariable>NVD_API_KEY</nvdApiKeyEnvironmentVariable>")
                .contains("config/owasp-suppressions.xml");

        Path suppressionsPath = resolveRepoPath("config/owasp-suppressions.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        Document suppressionsDoc = factory.newDocumentBuilder().parse(suppressionsPath.toFile());
        assertThat(suppressionsDoc.getDocumentElement().getLocalName()).isEqualTo("suppressions");

        Yaml yaml = new Yaml();
        try (InputStream is = Files.newInputStream(resolveRepoPath(".github/dependabot.yml"))) {
            Map<String, Object> dependabot = yaml.load(is);
            assertThat(dependabot.get("version")).isEqualTo(2);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> updates = (List<Map<String, Object>>) dependabot.get("updates");
            assertThat(updates).extracting(u -> u.get("package-ecosystem"))
                    .contains("maven", "docker", "github-actions");
            assertThat(updates)
                    .filteredOn(u -> "docker".equals(u.get("package-ecosystem")))
                    .extracting(u -> u.get("directory"))
                    .containsExactlyInAnyOrder("/ingestion-poller", "/worker-service", "/batch-etl");
        }

        for (String dockerfilePath : List.of(
                "ingestion-poller/Dockerfile",
                "worker-service/Dockerfile",
                "batch-etl/Dockerfile")) {
            String dockerfile = Files.readString(resolveRepoPath(dockerfilePath));
            assertThat(dockerfile)
                    .withFailMessage("%s must run as non-root user 10001:10001 and use exec java entrypoint", dockerfilePath)
                    .contains("USER 10001:10001")
                    .contains("exec java");
        }

        try (InputStream is = Files.newInputStream(resolveRepoPath(".github/workflows/security-audit.yml"))) {
            Map<String, Object> workflow = yaml.load(is);
            assertThat(workflow).containsKey("jobs");
            Object rawOn = workflow.containsKey("on") ? workflow.get("on") : workflow.get(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> onSection = (Map<String, Object>) rawOn;
            assertThat(onSection).containsKeys("schedule", "workflow_dispatch");
            String workflowRaw = Files.readString(resolveRepoPath(".github/workflows/security-audit.yml"));
            assertThat(workflowRaw)
                    .contains("-Psecurity-audit")
                    .contains("secrets.NVD_API_KEY");
        }

        assertServiceDeploymentHardening("infrastructure/k8s/ingestion-poller-deployment.yaml", 9081, false);
        assertServiceDeploymentHardening("infrastructure/k8s/worker-service-deployment.yaml", 9082, true);
    }

    @SuppressWarnings("unchecked")
    private void assertServiceDeploymentHardening(String manifestPath, int expectedManagementPort, boolean hasDatasource)
            throws Exception {
        Yaml yaml = new Yaml();
        Map<String, Object> doc;
        try (InputStream is = Files.newInputStream(resolveRepoPath(manifestPath))) {
            doc = yaml.load(is);
        }
        assertThat(doc.get("kind")).isEqualTo("Deployment");
        Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
        Map<String, Object> template = (Map<String, Object>) spec.get("template");
        Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");
        Map<String, Object> securityContext = (Map<String, Object>) podSpec.get("securityContext");
        assertThat(securityContext.get("runAsNonRoot")).isEqualTo(true);

        List<Map<String, Object>> containers = (List<Map<String, Object>>) podSpec.get("containers");
        assertThat(containers).hasSize(1);
        Map<String, Object> container = containers.get(0);
        List<Map<String, Object>> envList = (List<Map<String, Object>>) container.get("env");

        assertThat(findEnvValue(envList, "SPRING_PROFILES_ACTIVE")).isEqualTo("prod");
        assertThat(findEnvValue(envList, "SPRING_RABBITMQ_SSL_ENABLED")).isEqualTo("true");
        assertThat(findEnvValue(envList, "MANAGEMENT_SERVER_PORT")).isEqualTo(String.valueOf(expectedManagementPort));
        if (hasDatasource) {
            assertThat(findEnvValue(envList, "SPRING_DATASOURCE_URL")).contains("sslmode=require");
        }

        Map<String, Object> livenessGet = (Map<String, Object>) ((Map<String, Object>) container.get("livenessProbe")).get("httpGet");
        assertThat(livenessGet.get("path")).isEqualTo("/actuator/health/liveness");
        assertThat(livenessGet.get("port")).isEqualTo(expectedManagementPort);

        Map<String, Object> readinessGet = (Map<String, Object>) ((Map<String, Object>) container.get("readinessProbe")).get("httpGet");
        assertThat(readinessGet.get("path")).isEqualTo("/actuator/health/readiness");
        assertThat(readinessGet.get("port")).isEqualTo(expectedManagementPort);
    }

    private static void assertEnvVarOnlyPlaceholder(String filePath, String propertyName, Object rawValue) {
        String value = String.valueOf(rawValue);
        assertThat(value)
                .withFailMessage("%s property %s must reference environment variables without a literal default (found: %s)",
                        filePath, propertyName, value)
                .matches("^\\$\\{[A-Z0-9_]+(?::\\$\\{[A-Z0-9_]+\\})?\\}$");
    }

    private static String findEnvValue(List<Map<String, Object>> envList, String name) {
        return envList.stream()
                .filter(e -> name.equals(e.get("name")))
                .map(e -> (String) e.get("value"))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private void assertNoInlineSensitiveEnvVars(String manifestPath, Map<String, Object> doc) {
        Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
        Map<String, Object> template = spec.containsKey("jobTemplate")
                ? (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) spec.get("jobTemplate")).get("spec")).get("template")
                : (Map<String, Object>) spec.get("template");
        Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");
        List<Map<String, Object>> containers = (List<Map<String, Object>>) podSpec.get("containers");

        for (Map<String, Object> container : containers) {
            List<Map<String, Object>> envList = (List<Map<String, Object>>) container.get("env");
            for (Map<String, Object> env : envList) {
                String name = (String) env.get("name");
                if (SENSITIVE_K8S_ENV_VARS.contains(name)) {
                    assertThat(env.get("value"))
                            .withFailMessage("%s must not set inline 'value' for %s", manifestPath, name)
                            .isNull();
                    Map<String, Object> valueFrom = (Map<String, Object>) env.get("valueFrom");
                    assertThat(valueFrom).containsKey("secretKeyRef");
                }
            }
        }
    }

    private static Path resolveRepoPath(String relativePath) {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git")) || Files.exists(current.resolve("mvnw"))) {
                Path resolved = current.resolve(relativePath);
                if (Files.exists(resolved)) {
                    return resolved;
                }
            }
            current = current.getParent();
        }
        throw new IllegalArgumentException("Unable to resolve repository path: " + relativePath);
    }
}
