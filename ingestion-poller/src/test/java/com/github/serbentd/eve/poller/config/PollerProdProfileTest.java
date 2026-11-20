package com.github.serbentd.eve.poller.config;

import com.github.serbentd.eve.common.security.ProductionSecretValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ingestion Poller Production Profile Secret Validation")
class PollerProdProfileTest {

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(SecurityConfig.class);

    @Test
    @DisplayName("Should not register ProductionSecretValidator when prod profile is inactive")
    void defaultProfile_shouldNotRegisterProductionSecretValidator() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ProductionSecretValidator.class);
        });
    }

    @Test
    @DisplayName("Should fail startup under prod profile when required secrets are missing")
    void prodProfile_withoutRequiredSecrets_shouldFailFast() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("Should fail startup under prod profile when local fallback secrets are supplied")
    void prodProfile_withLocalPlaceholderSecrets_shouldFailFast() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.rabbitmq.host=rabbitmq.internal",
                        "RABBITMQ_PASS=eve_password",
                        "EVE_SECURITY_PASSWORD=eve_password",
                        "EVE_SECURITY_API_KEYS=eve_local_api_key",
                        "EVE_SECURITY_ACTUATOR_API_KEYS=eve_local_actuator_key"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Production secret validation failed");
                });
    }

    @Test
    @DisplayName("Should start cleanly under prod profile when strong secrets and TLS are configured")
    void prodProfile_withValidSecretsAndTls_shouldStartCleanly() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.rabbitmq.host=rabbitmq.internal",
                        "RABBITMQ_PASS=prod-rabbit-secret-9001",
                        "EVE_SECURITY_PASSWORD=prod-basic-auth-secret-9001",
                        "EVE_SECURITY_API_KEYS=prod-admin-key-9001",
                        "EVE_SECURITY_ACTUATOR_API_KEYS=prod-actuator-key-9001"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ProductionSecretValidator.class);
                });
    }
}
