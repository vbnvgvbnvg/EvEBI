package com.github.serbentd.eve.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Shared immutable security configuration properties supporting role-scoped API keys
 * and multi-key lists for zero-downtime key rotation across Eve-BI services.
 *
 * @param username        HTTP Basic authentication username for administrative access
 * @param password        HTTP Basic authentication password for administrative access
 * @param apiKeyHeader    HTTP header carrying the API key (e.g. X-API-Key)
 * @param adminApiKeys    active API keys granting full administrative access (ADMIN, ACTUATOR, BATCH_OPERATOR)
 * @param actuatorApiKeys active API keys granting read-only management access (ACTUATOR only)
 * @param operatorApiKeys active API keys granting operational batch trigger access (BATCH_OPERATOR only)
 */
@Validated
@ConfigurationProperties(prefix = "eve.security")
public record EveSecurityProperties(
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String apiKeyHeader,
        @NotEmpty List<@NotBlank String> adminApiKeys,
        @NotEmpty List<@NotBlank String> actuatorApiKeys,
        List<@NotBlank String> operatorApiKeys
) {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_ACTUATOR = "ACTUATOR";
    public static final String ROLE_BATCH_OPERATOR = "BATCH_OPERATOR";
    public static final String LIVENESS_PROBE_PATH = "/actuator/health/liveness";
    public static final String READINESS_PROBE_PATH = "/actuator/health/readiness";
    public static final String ACTUATOR_PATH_PATTERN = "/actuator/**";
    public static final String BATCH_JOBS_API_PATTERN = "/api/v1/jobs/**";
}
