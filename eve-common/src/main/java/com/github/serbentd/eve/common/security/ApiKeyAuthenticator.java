package com.github.serbentd.eve.common.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Constant-time API key authenticator supporting role-scoped key groups
 * ({@code ADMIN}, {@code ACTUATOR}, {@code BATCH_OPERATOR}) and multi-key lists
 * for zero-downtime secret rotation.
 */
public class ApiKeyAuthenticator {

    private static final String API_KEY_PRINCIPAL = "api-key-client";

    private final List<byte[]> adminKeys;
    private final List<byte[]> actuatorKeys;
    private final List<byte[]> operatorKeys;

    public ApiKeyAuthenticator(EveSecurityProperties properties) {
        this.adminKeys = encodeKeys(properties.adminApiKeys());
        this.actuatorKeys = encodeKeys(properties.actuatorApiKeys());
        this.operatorKeys = encodeKeys(properties.operatorApiKeys());
    }

    /**
     * Authenticates a provided API key against configured role-scoped key lists in constant time.
     *
     * @param providedKey raw header value from the incoming request
     * @return authenticated token with granted authorities if the key matches at least one active scope
     */
    public Optional<UsernamePasswordAuthenticationToken> authenticate(String providedKey) {
        if (providedKey == null || providedKey.isBlank()) {
            return Optional.empty();
        }

        byte[] providedBytes = providedKey.getBytes(StandardCharsets.UTF_8);
        Set<String> matchedRoles = new LinkedHashSet<>();

        if (matchesAny(providedBytes, adminKeys)) {
            matchedRoles.add(EveSecurityProperties.ROLE_ADMIN);
            matchedRoles.add(EveSecurityProperties.ROLE_ACTUATOR);
            matchedRoles.add(EveSecurityProperties.ROLE_BATCH_OPERATOR);
        }
        if (matchesAny(providedBytes, actuatorKeys)) {
            matchedRoles.add(EveSecurityProperties.ROLE_ACTUATOR);
        }
        if (matchesAny(providedBytes, operatorKeys)) {
            matchedRoles.add(EveSecurityProperties.ROLE_BATCH_OPERATOR);
        }

        if (matchedRoles.isEmpty()) {
            return Optional.empty();
        }

        List<SimpleGrantedAuthority> authorities = matchedRoles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();

        return Optional.of(UsernamePasswordAuthenticationToken.authenticated(
                API_KEY_PRINCIPAL, null, authorities));
    }

    private static boolean matchesAny(byte[] providedBytes, List<byte[]> candidateKeys) {
        boolean matched = false;
        for (byte[] candidate : candidateKeys) {
            matched |= MessageDigest.isEqual(providedBytes, candidate);
        }
        return matched;
    }

    private static List<byte[]> encodeKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<byte[]> encoded = new ArrayList<>(keys.size());
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                encoded.add(key.getBytes(StandardCharsets.UTF_8));
            }
        }
        return List.copyOf(encoded);
    }
}
