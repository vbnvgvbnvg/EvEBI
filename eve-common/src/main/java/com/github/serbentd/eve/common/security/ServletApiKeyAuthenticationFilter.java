package com.github.serbentd.eve.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Shared stateless servlet filter authenticating requests via {@link ApiKeyAuthenticator}.
 */
public class ServletApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final String headerName;
    private final ApiKeyAuthenticator authenticator;

    public ServletApiKeyAuthenticationFilter(EveSecurityProperties securityProperties) {
        this.headerName = securityProperties.apiKeyHeader();
        this.authenticator = new ApiKeyAuthenticator(securityProperties);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String providedKey = request.getHeader(headerName);
            authenticator.authenticate(providedKey).ifPresent(authentication -> {
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        filterChain.doFilter(request, response);
    }
}
