package com.github.serbentd.eve.common.security;

import jakarta.servlet.FilterChain;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApiKeyAuthenticatorTest {

    private static final String HEADER_NAME = "X-API-Key";
    private static final String ADMIN_KEY_PRIMARY = "admin-key-v2";
    private static final String ADMIN_KEY_SECONDARY = "admin-key-v1";
    private static final String ACTUATOR_KEY_PRIMARY = "actuator-key-v2";
    private static final String ACTUATOR_KEY_SECONDARY = "actuator-key-v1";
    private static final String OPERATOR_KEY_PRIMARY = "operator-key-v2";
    private static final String OPERATOR_KEY_SECONDARY = "operator-key-v1";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Should grant ADMIN, ACTUATOR, and BATCH_OPERATOR roles for primary and rotated admin keys")
    void authenticate_withAdminKeys_shouldGrantAllRoles() {
        ApiKeyAuthenticator authenticator = new ApiKeyAuthenticator(createSampleProperties());

        for (String key : List.of(ADMIN_KEY_PRIMARY, ADMIN_KEY_SECONDARY)) {
            Optional<UsernamePasswordAuthenticationToken> auth = authenticator.authenticate(key);
            assertThat(auth).isPresent();
            assertThat(extractRoles(auth.get())).containsExactlyInAnyOrder(
                    "ROLE_ADMIN", "ROLE_ACTUATOR", "ROLE_BATCH_OPERATOR"
            );
        }
    }

    @Test
    @DisplayName("Should grant only ROLE_ACTUATOR for primary and rotated actuator keys")
    void authenticate_withActuatorKeys_shouldGrantOnlyActuatorRole() {
        ApiKeyAuthenticator authenticator = new ApiKeyAuthenticator(createSampleProperties());

        for (String key : List.of(ACTUATOR_KEY_PRIMARY, ACTUATOR_KEY_SECONDARY)) {
            Optional<UsernamePasswordAuthenticationToken> auth = authenticator.authenticate(key);
            assertThat(auth).isPresent();
            assertThat(extractRoles(auth.get())).containsExactly("ROLE_ACTUATOR");
        }
    }

    @Test
    @DisplayName("Should grant only ROLE_BATCH_OPERATOR for primary and rotated operator keys")
    void authenticate_withOperatorKeys_shouldGrantOnlyOperatorRole() {
        ApiKeyAuthenticator authenticator = new ApiKeyAuthenticator(createSampleProperties());

        for (String key : List.of(OPERATOR_KEY_PRIMARY, OPERATOR_KEY_SECONDARY)) {
            Optional<UsernamePasswordAuthenticationToken> auth = authenticator.authenticate(key);
            assertThat(auth).isPresent();
            assertThat(extractRoles(auth.get())).containsExactly("ROLE_BATCH_OPERATOR");
        }
    }

    @Test
    @DisplayName("Should reject null, blank, or unknown keys and handle null/empty optional key lists")
    void authenticate_withInvalidKeysOrEmptyOptionalList_shouldReturnEmpty() {
        EveSecurityProperties propsWithoutOperatorKeys = new EveSecurityProperties(
                "admin", "password", HEADER_NAME,
                List.of(ADMIN_KEY_PRIMARY),
                Arrays.asList(ACTUATOR_KEY_PRIMARY, "", null),
                null
        );
        ApiKeyAuthenticator authenticator = new ApiKeyAuthenticator(propsWithoutOperatorKeys);

        assertThat(authenticator.authenticate(null)).isEmpty();
        assertThat(authenticator.authenticate("")).isEmpty();
        assertThat(authenticator.authenticate("   ")).isEmpty();
        assertThat(authenticator.authenticate("unknown-key")).isEmpty();

        EveSecurityProperties propsWithEmptyList = new EveSecurityProperties(
                "admin", "password", HEADER_NAME,
                List.of(ADMIN_KEY_PRIMARY),
                List.of(ACTUATOR_KEY_PRIMARY),
                List.of()
        );
        assertThat(new ApiKeyAuthenticator(propsWithEmptyList).authenticate(OPERATOR_KEY_PRIMARY)).isEmpty();
    }

    @Test
    @DisplayName("ServletApiKeyAuthenticationFilter should populate SecurityContext when valid key is provided and skip when already authenticated")
    void servletFilter_shouldPopulateSecurityContextAndRespectExistingAuth() throws Exception {
        ServletApiKeyAuthenticationFilter filter = new ServletApiKeyAuthenticationFilter(createSampleProperties());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER_NAME, ACTUATOR_KEY_PRIMARY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(extractRoles(auth)).containsExactly("ROLE_ACTUATOR");
        assertThat(auth.getDetails()).isNotNull();
        verify(filterChain).doFilter(request, response);

        // When SecurityContext already holds an authentication, filter should not overwrite it
        FilterChain secondChain = mock(FilterChain.class);
        MockHttpServletRequest secondRequest = new MockHttpServletRequest();
        secondRequest.addHeader(HEADER_NAME, ADMIN_KEY_PRIMARY);
        filter.doFilter(secondRequest, response, secondChain);

        assertThat(extractRoles(SecurityContextHolder.getContext().getAuthentication()))
                .containsExactly("ROLE_ACTUATOR");
        verify(secondChain).doFilter(secondRequest, response);
    }

    @Test
    @DisplayName("EveSecurityProperties should enforce Jakarta validation constraints")
    void eveSecurityProperties_shouldValidateConstraints() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(createSampleProperties())).isEmpty();

            EveSecurityProperties invalid = new EveSecurityProperties(
                    "", "  ", "", List.of(), List.of("") , List.of("")
            );
            Set<String> paths = validator.validate(invalid).stream()
                    .map(v -> v.getPropertyPath().toString())
                    .collect(Collectors.toSet());
            assertThat(paths).contains("username", "password", "apiKeyHeader", "adminApiKeys");
        }
    }

    private static EveSecurityProperties createSampleProperties() {
        return new EveSecurityProperties(
                "admin",
                "password",
                HEADER_NAME,
                List.of(ADMIN_KEY_PRIMARY, ADMIN_KEY_SECONDARY),
                List.of(ACTUATOR_KEY_PRIMARY, ACTUATOR_KEY_SECONDARY),
                List.of(OPERATOR_KEY_PRIMARY, OPERATOR_KEY_SECONDARY)
        );
    }

    private static Set<String> extractRoles(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
