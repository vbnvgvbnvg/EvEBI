package com.github.serbentd.eve.poller.config;

import com.github.serbentd.eve.common.security.ApiKeyAuthenticator;
import com.github.serbentd.eve.common.security.EveSecurityProperties;
import com.github.serbentd.eve.common.security.ProductionSecretValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.server.WebFilter;

/**
 * Reactive Spring Security configuration for {@code ingestion-poller}.
 * <p>
 * Leaves Kubernetes liveness and readiness probes unauthenticated while protecting
 * all remaining Actuator endpoints via role-scoped API keys (supporting zero-downtime key rotation)
 * or HTTP Basic authentication.
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(EveSecurityProperties.class)
@Import(ProductionSecretValidator.class)
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http,
            EveSecurityProperties securityProperties) {
        return http
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .httpBasic(Customizer.withDefaults())
                .addFilterAt(apiKeyWebFilter(securityProperties), SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.GET,
                                EveSecurityProperties.LIVENESS_PROBE_PATH,
                                EveSecurityProperties.READINESS_PROBE_PATH).permitAll()
                        .pathMatchers(EveSecurityProperties.ACTUATOR_PATH_PATTERN).hasAnyRole(
                                EveSecurityProperties.ROLE_ACTUATOR,
                                EveSecurityProperties.ROLE_ADMIN)
                        .anyExchange().denyAll()
                )
                .build();
    }

    @Bean
    public MapReactiveUserDetailsService userDetailsService(
            EveSecurityProperties securityProperties,
            PasswordEncoder passwordEncoder) {
        UserDetails admin = User.builder()
                .username(securityProperties.username())
                .password(passwordEncoder.encode(securityProperties.password()))
                .roles(EveSecurityProperties.ROLE_ADMIN, EveSecurityProperties.ROLE_ACTUATOR)
                .build();
        return new MapReactiveUserDetailsService(admin);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private WebFilter apiKeyWebFilter(EveSecurityProperties securityProperties) {
        ApiKeyAuthenticator authenticator = new ApiKeyAuthenticator(securityProperties);
        String headerName = securityProperties.apiKeyHeader();
        return (exchange, chain) -> {
            String providedKey = exchange.getRequest().getHeaders().getFirst(headerName);
            return authenticator.authenticate(providedKey)
                    .map(auth -> chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                    .orElseGet(() -> chain.filter(exchange));
        };
    }
}
