package com.github.serbentd.eve.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-fast startup validator active exclusively under the {@code prod} profile.
 * <p>
 * Verifies that all security, database, Liquibase, and RabbitMQ credentials are
 * externally supplied (never matching local development fallback placeholders or
 * unresolved {@code ${...}} templates), enforces role-scoped API key separation,
 * and ensures TLS/SSL transport encryption is active for PostgreSQL and RabbitMQ.
 */
@Component
@Profile("prod")
public class ProductionSecretValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ProductionSecretValidator.class);

    private static final Set<String> FORBIDDEN_PLACEHOLDERS = Set.of(
            "eve_password",
            "eve_local_api_key",
            "eve_local_actuator_key",
            "eve_local_operator_key",
            "test-password",
            "test-api-key",
            "test-admin-key-rotated",
            "test-actuator-key",
            "test-actuator-key-rotated",
            "test-operator-key",
            "test-operator-key-rotated",
            "changeme",
            "password",
            "admin",
            "secret",
            "default",
            "replace_me"
    );

    private static final Set<String> REQUIRED_POSTGRES_SSL_MODES = Set.of("require", "verify-full");
    private static final String POSTGRES_URL_PREFIX = "jdbc:postgresql:";
    private static final String H2_URL_PREFIX = "jdbc:h2:";

    private final EveSecurityProperties securityProperties;
    private final Environment environment;

    public ProductionSecretValidator(EveSecurityProperties securityProperties, Environment environment) {
        this.securityProperties = securityProperties;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> violations = new ArrayList<>();

        validateSecurityProperties(violations);
        validateDatasource(violations);
        validateLiquibase(violations);
        validateRabbitMq(violations);

        if (!violations.isEmpty()) {
            String summary = String.join("; ", violations);
            log.error("Production secret validation failed with {} violation(s): {}", violations.size(), summary);
            throw new IllegalStateException("Production secret validation failed: " + summary);
        }

        log.info("Production secret and transport TLS invariants verified successfully.");
    }

    private void validateSecurityProperties(List<String> violations) {
        validateSecretValue("eve.security.password", securityProperties.password(), violations);
        if (securityProperties.username() != null
                && securityProperties.password() != null
                && !securityProperties.username().isBlank()
                && securityProperties.username().trim().equalsIgnoreCase(securityProperties.password().trim())) {
            violations.add("eve.security.password must not match eve.security.username");
        }

        validateKeyList("eve.security.admin-api-keys", securityProperties.adminApiKeys(), violations);
        validateKeyList("eve.security.actuator-api-keys", securityProperties.actuatorApiKeys(), violations);

        if (securityProperties.operatorApiKeys() != null && !securityProperties.operatorApiKeys().isEmpty()) {
            validateKeyList("eve.security.operator-api-keys", securityProperties.operatorApiKeys(), violations);
        }

        validateRoleKeySeparation(violations);
    }

    private void validateRoleKeySeparation(List<String> violations) {
        Set<String> adminKeys = toNormalizedSet(securityProperties.adminApiKeys());
        Set<String> actuatorKeys = toNormalizedSet(securityProperties.actuatorApiKeys());
        Set<String> operatorKeys = toNormalizedSet(securityProperties.operatorApiKeys());

        if (hasIntersection(adminKeys, actuatorKeys)) {
            violations.add("eve.security.admin-api-keys and eve.security.actuator-api-keys must not share keys");
        }
        if (hasIntersection(adminKeys, operatorKeys)) {
            violations.add("eve.security.admin-api-keys and eve.security.operator-api-keys must not share keys");
        }
        if (hasIntersection(actuatorKeys, operatorKeys)) {
            violations.add("eve.security.actuator-api-keys and eve.security.operator-api-keys must not share keys");
        }

        String password = securityProperties.password();
        if (password != null && !password.isBlank()) {
            String trimmedPassword = password.trim();
            if (adminKeys.contains(trimmedPassword)
                    || actuatorKeys.contains(trimmedPassword)
                    || operatorKeys.contains(trimmedPassword)) {
                violations.add("eve.security.password must not reuse an API key");
            }
        }
    }

    private void validateDatasource(List<String> violations) {
        String datasourceUrl = resolveOptionalProperty("spring.datasource.url", violations);
        if (datasourceUrl == null || datasourceUrl.isBlank()) {
            return;
        }

        String lowerUrl = datasourceUrl.toLowerCase(Locale.ROOT);
        if (lowerUrl.startsWith(H2_URL_PREFIX)) {
            violations.add("spring.datasource.url must not use in-memory H2 in prod profile");
        } else if (lowerUrl.startsWith(POSTGRES_URL_PREFIX) && !hasRequiredPostgresSslMode(datasourceUrl)) {
            violations.add("spring.datasource.url must enforce sslmode=require or sslmode=verify-full in prod profile");
        }

        validateRequiredEnvironmentSecret("spring.datasource.password", violations);
    }

    private void validateLiquibase(List<String> violations) {
        boolean hasChangelog = environment.containsProperty("spring.liquibase.change-log");
        Boolean enabled = environment.getProperty("spring.liquibase.enabled", Boolean.class);
        if (Boolean.FALSE.equals(enabled) || (!hasChangelog && !Boolean.TRUE.equals(enabled))) {
            return;
        }

        validateRequiredEnvironmentSecret("spring.liquibase.password", violations);
    }

    private void validateRabbitMq(List<String> violations) {
        String rabbitHost = resolveOptionalProperty("spring.rabbitmq.host", violations);
        if (rabbitHost == null || rabbitHost.isBlank()) {
            return;
        }

        Boolean sslEnabled = environment.getProperty("spring.rabbitmq.ssl.enabled", Boolean.class);
        if (!Boolean.TRUE.equals(sslEnabled)) {
            violations.add("spring.rabbitmq.ssl.enabled must be true in prod profile");
        }

        validateRequiredEnvironmentSecret("spring.rabbitmq.password", violations);
    }

    private void validateKeyList(String propertyName, List<String> keys, List<String> violations) {
        if (keys == null || keys.isEmpty()) {
            violations.add(propertyName + " must not be empty");
            return;
        }
        for (int i = 0; i < keys.size(); i++) {
            validateSecretValue(propertyName + "[" + i + "]", keys.get(i), violations);
        }
    }

    private void validateRequiredEnvironmentSecret(String propertyName, List<String> violations) {
        String value = resolveOptionalProperty(propertyName, violations);
        if (value == null) {
            if (violations.stream().noneMatch(v -> v.contains(propertyName))) {
                violations.add(propertyName + " must be configured in prod profile");
            }
            return;
        }
        validateSecretValue(propertyName, value, violations);
    }

    private String resolveOptionalProperty(String propertyName, List<String> violations) {
        try {
            return environment.getProperty(propertyName);
        } catch (IllegalArgumentException ex) {
            log.debug("Failed to resolve property '{}': {}", propertyName, ex.getMessage());
            violations.add(propertyName + " could not be resolved (" + ex.getMessage() + ")");
            return null;
        }
    }

    private static void validateSecretValue(String propertyName, String value, List<String> violations) {
        if (value == null || value.isBlank()) {
            violations.add(propertyName + " must not be blank");
            return;
        }

        String trimmed = value.trim();
        if (trimmed.startsWith("${") || trimmed.startsWith("<REPLACE_")) {
            violations.add(propertyName + " contains an unresolved placeholder template");
            return;
        }

        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_PLACEHOLDERS.contains(normalized)) {
            violations.add(propertyName + " must not use a local development placeholder value");
        }
    }

    private static boolean hasRequiredPostgresSslMode(String jdbcUrl) {
        int queryIndex = jdbcUrl.indexOf('?');
        if (queryIndex < 0 || queryIndex == jdbcUrl.length() - 1) {
            return false;
        }
        String query = jdbcUrl.substring(queryIndex + 1);
        for (String param : query.split("&")) {
            int eqIndex = param.indexOf('=');
            if (eqIndex > 0) {
                String key = param.substring(0, eqIndex).trim().toLowerCase(Locale.ROOT);
                String val = param.substring(eqIndex + 1).trim().toLowerCase(Locale.ROOT);
                if ("sslmode".equals(key) && REQUIRED_POSTGRES_SSL_MODES.contains(val)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> toNormalizedSet(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                result.add(key.trim());
            }
        }
        return result;
    }

    private static boolean hasIntersection(Set<String> first, Set<String> second) {
        for (String item : first) {
            if (second.contains(item)) {
                return true;
            }
        }
        return false;
    }
}
