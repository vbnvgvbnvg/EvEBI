package com.github.serbentd.eve.batch.config;

import com.github.serbentd.eve.common.security.ProductionSecretValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Batch ETL Production Profile Secret Validation")
class BatchProdProfileTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    SecurityAutoConfiguration.class))
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
                        "POSTGRES_BATCH_PASSWORD=eve_password",
                        "POSTGRES_LIQUIBASE_PASSWORD=eve_password",
                        "RABBITMQ_PASS=eve_password",
                        "EVE_SECURITY_PASSWORD=eve_password",
                        "EVE_SECURITY_API_KEYS=eve_local_api_key",
                        "EVE_SECURITY_ACTUATOR_API_KEYS=eve_local_actuator_key",
                        "EVE_SECURITY_OPERATOR_API_KEYS=eve_local_operator_key"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Production secret validation failed");
                });
    }

    @Test
    @DisplayName("Should start cleanly under prod profile when service env secrets and TLS are configured")
    void prodProfile_withServiceEnvSecretsAndTls_shouldStartCleanly() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.rabbitmq.host=rabbitmq.internal",
                        "POSTGRES_BATCH_PASSWORD=prod-batch-db-secret-9001",
                        "POSTGRES_LIQUIBASE_PASSWORD=prod-liquibase-db-secret-9001",
                        "RABBITMQ_PASS=prod-rabbit-secret-9001",
                        "EVE_SECURITY_PASSWORD=prod-basic-auth-secret-9001",
                        "EVE_SECURITY_API_KEYS=prod-admin-key-9001",
                        "EVE_SECURITY_ACTUATOR_API_KEYS=prod-actuator-key-9001",
                        "EVE_SECURITY_OPERATOR_API_KEYS=prod-operator-key-9001"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ProductionSecretValidator.class);
                });
    }

    @Test
    @DisplayName("Should start cleanly under prod profile when Kubernetes SPRING_* OS env secrets and TLS are configured")
    void prodProfile_withKubernetesSpringEnvSecretsAndTls_shouldStartCleanly() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod")
                .withInitializer(ctx -> TestPropertyValues.of(
                        "SPRING_DATASOURCE_URL=jdbc:postgresql://postgres.eve-system.svc.cluster.local:5432/eve_bi?sslmode=require",
                        "SPRING_DATASOURCE_PASSWORD=prod-batch-db-secret-9002",
                        "SPRING_LIQUIBASE_PASSWORD=prod-liquibase-db-secret-9002",
                        "SPRING_RABBITMQ_HOST=rabbitmq.eve-system.svc.cluster.local",
                        "SPRING_RABBITMQ_SSL_ENABLED=true",
                        "SPRING_RABBITMQ_PASSWORD=prod-rabbit-secret-9002",
                        "EVE_SECURITY_PASSWORD=prod-basic-auth-secret-9002",
                        "EVE_SECURITY_API_KEYS=prod-admin-key-9002",
                        "EVE_SECURITY_ACTUATOR_API_KEYS=prod-actuator-key-9002",
                        "EVE_SECURITY_OPERATOR_API_KEYS=prod-operator-key-9002"
                ).applyTo(ctx.getEnvironment(), TestPropertyValues.Type.SYSTEM_ENVIRONMENT))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ProductionSecretValidator.class);
                });
    }
}
