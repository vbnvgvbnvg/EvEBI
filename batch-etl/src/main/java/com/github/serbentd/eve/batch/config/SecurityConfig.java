package com.github.serbentd.eve.batch.config;

import com.github.serbentd.eve.common.security.EveSecurityProperties;
import com.github.serbentd.eve.common.security.ProductionSecretValidator;
import com.github.serbentd.eve.common.security.ServletApiKeyAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for {@code batch-etl}.
 * <p>
 * Preserves unauthenticated access for Kubernetes liveness and readiness probes while
 * enforcing role-scoped API key (supporting zero-downtime key rotation) or HTTP Basic
 * authentication on operational batch REST endpoints ({@code /api/v1/jobs/**}) and Actuator endpoints.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(EveSecurityProperties.class)
@Import(ProductionSecretValidator.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            EveSecurityProperties securityProperties) throws Exception {
        ServletApiKeyAuthenticationFilter apiKeyFilter =
                new ServletApiKeyAuthenticationFilter(securityProperties);

        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Stateless non-browser API (X-API-Key / HTTP Basic without session cookies); POST /api/v1/jobs/** requires CSRF disabled
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .httpBasic(Customizer.withDefaults())
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET,
                                EveSecurityProperties.LIVENESS_PROBE_PATH,
                                EveSecurityProperties.READINESS_PROBE_PATH).permitAll()
                        .requestMatchers(EveSecurityProperties.ACTUATOR_PATH_PATTERN).hasAnyRole(
                                EveSecurityProperties.ROLE_ACTUATOR,
                                EveSecurityProperties.ROLE_ADMIN)
                        .requestMatchers(EveSecurityProperties.BATCH_JOBS_API_PATTERN).hasAnyRole(
                                EveSecurityProperties.ROLE_BATCH_OPERATOR,
                                EveSecurityProperties.ROLE_ADMIN)
                        .anyRequest().denyAll()
                )
                .build();
    }

    @Bean
    public UserDetailsService userDetailsService(
            EveSecurityProperties securityProperties,
            PasswordEncoder passwordEncoder) {
        UserDetails admin = User.builder()
                .username(securityProperties.username())
                .password(passwordEncoder.encode(securityProperties.password()))
                .roles(
                        EveSecurityProperties.ROLE_ADMIN,
                        EveSecurityProperties.ROLE_ACTUATOR,
                        EveSecurityProperties.ROLE_BATCH_OPERATOR
                )
                .build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
