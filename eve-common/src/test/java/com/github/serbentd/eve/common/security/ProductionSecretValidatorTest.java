package com.github.serbentd.eve.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Production Secret & TLS Validator Tests")
class ProductionSecretValidatorTest {

    private static final String PROD_USERNAME = "eve_prod_admin";
    private static final String PROD_PASSWORD = "prod-strong-basic-auth-secret-99";
    private static final String PROD_ADMIN_KEY = "prod-admin-key-rotatable-1";
    private static final String PROD_ACTUATOR_KEY = "prod-actuator-key-rotatable-1";
    private static final String PROD_OPERATOR_KEY = "prod-operator-key-rotatable-1";
    private static final String PROD_DB_PASSWORD = "prod-db-strong-secret-42";
    private static final String PROD_LIQUIBASE_PASSWORD = "prod-liquibase-strong-secret-42";
    private static final String PROD_RABBIT_PASSWORD = "prod-rabbit-strong-secret-42";

    @Test
    @DisplayName("Should pass validation when all production secrets and TLS flags are properly configured")
    void validate_withValidProdSecretsAndTls_shouldSucceed() {
        MockEnvironment env = validProdEnvironment();
        ProductionSecretValidator validator = new ProductionSecretValidator(validSecurityProperties(), env);

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should accept sslmode=verify-full for PostgreSQL datasource in prod profile")
    void validate_withPostgresVerifyFullSslMode_shouldSucceed() {
        MockEnvironment env = validProdEnvironment()
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://pg.internal:5432/eve_bi?sslmode=verify-full");
        ProductionSecretValidator validator = new ProductionSecretValidator(validSecurityProperties(), env);

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should fail when EveSecurityProperties contains local development placeholder secrets")
    void validate_withLocalDevPlaceholderInSecurityProps_shouldThrow() {
        EveSecurityProperties insecureProps = new EveSecurityProperties(
                PROD_USERNAME,
                "eve_password",
                "X-API-Key",
                List.of("eve_local_api_key"),
                List.of(PROD_ACTUATOR_KEY),
                List.of(PROD_OPERATOR_KEY)
        );
        ProductionSecretValidator validator = new ProductionSecretValidator(insecureProps, validProdEnvironment());

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eve.security.password")
                .hasMessageContaining("eve.security.admin-api-keys[0]");
    }

    @Test
    @DisplayName("Should fail when API keys are reused across privilege scopes or match password or username")
    void validate_withSharedKeysAcrossRoles_shouldThrow() {
        EveSecurityProperties overlappingProps = new EveSecurityProperties(
                PROD_USERNAME,
                PROD_ADMIN_KEY,
                "X-API-Key",
                List.of(PROD_ADMIN_KEY),
                List.of(PROD_ADMIN_KEY, PROD_OPERATOR_KEY),
                List.of(PROD_ADMIN_KEY, PROD_OPERATOR_KEY)
        );
        ProductionSecretValidator validator = new ProductionSecretValidator(overlappingProps, validProdEnvironment());

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eve.security.admin-api-keys and eve.security.actuator-api-keys must not share keys")
                .hasMessageContaining("eve.security.admin-api-keys and eve.security.operator-api-keys must not share keys")
                .hasMessageContaining("eve.security.actuator-api-keys and eve.security.operator-api-keys must not share keys")
                .hasMessageContaining("must not reuse an API key");

        EveSecurityProperties usernameMatchesPassword = new EveSecurityProperties(
                PROD_USERNAME,
                PROD_USERNAME,
                "X-API-Key",
                List.of(PROD_ADMIN_KEY),
                List.of(PROD_ACTUATOR_KEY),
                List.of()
        );
        ProductionSecretValidator userPassValidator =
                new ProductionSecretValidator(usernameMatchesPassword, validProdEnvironment());

        assertThatThrownBy(userPassValidator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eve.security.password must not match eve.security.username");
    }

    @Test
    @DisplayName("Should fail when secrets are blank, empty, or unresolvable in environment")
    void validate_withBlankOrMissingSecrets_shouldThrow() {
        EveSecurityProperties blankProps = new EveSecurityProperties(
                PROD_USERNAME,
                "   ",
                "X-API-Key",
                List.of(),
                List.of("${UNRESOLVED_KEY}"),
                List.of("   ")
        );
        MockEnvironment missingSecretsEnv = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/eve_bi")
                .withProperty("spring.liquibase.change-log", "classpath:/db/changelog/db.changelog-master.xml")
                .withProperty("spring.rabbitmq.host", "rabbitmq.internal");

        ProductionSecretValidator validator = new ProductionSecretValidator(blankProps, missingSecretsEnv);

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eve.security.password must not be blank")
                .hasMessageContaining("eve.security.admin-api-keys must not be empty")
                .hasMessageContaining("eve.security.actuator-api-keys[0] contains an unresolved placeholder template")
                .hasMessageContaining("eve.security.operator-api-keys[0] must not be blank")
                .hasMessageContaining("spring.datasource.url must enforce sslmode=require")
                .hasMessageContaining("spring.datasource.password must be configured")
                .hasMessageContaining("spring.liquibase.password must be configured")
                .hasMessageContaining("spring.rabbitmq.password must be configured");
    }

    @Test
    @DisplayName("Should skip Liquibase validation when spring.liquibase.enabled is false and accept multi-param JDBC URLs")
    void validate_whenLiquibaseDisabledAndMultiParamJdbcUrl_shouldSucceed() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://pg.internal:5432/eve_bi?connectTimeout=10&sslmode=require")
                .withProperty("spring.datasource.password", PROD_DB_PASSWORD)
                .withProperty("spring.liquibase.change-log", "classpath:/db/changelog/db.changelog-master.xml")
                .withProperty("spring.liquibase.enabled", "false")
                .withProperty("spring.rabbitmq.host", "rabbitmq.internal")
                .withProperty("spring.rabbitmq.ssl.enabled", "true")
                .withProperty("spring.rabbitmq.password", PROD_RABBIT_PASSWORD);

        ProductionSecretValidator validator = new ProductionSecretValidator(validSecurityProperties(), env);
        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should fail when datasource uses insecure sslmode or H2 or placeholder password")
    void validate_withInsecureDatasource_shouldThrow() {
        MockEnvironment env = validProdEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/eve_bi?sslmode=prefer")
                .withProperty("spring.datasource.password", "eve_password");
        ProductionSecretValidator validator = new ProductionSecretValidator(validSecurityProperties(), env);

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sslmode=require")
                .hasMessageContaining("spring.datasource.password");

        MockEnvironment h2Env = validProdEnvironment()
                .withProperty("spring.datasource.url", "jdbc:h2:mem:prod_forbidden");
        ProductionSecretValidator h2Validator = new ProductionSecretValidator(validSecurityProperties(), h2Env);

        assertThatThrownBy(h2Validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("H2");
    }

    @Test
    @DisplayName("Should fail when RabbitMQ SSL is disabled or password is a placeholder")
    void validate_withInsecureRabbitMq_shouldThrow() {
        MockEnvironment env = validProdEnvironment()
                .withProperty("spring.rabbitmq.ssl.enabled", "false")
                .withProperty("spring.rabbitmq.password", "<REPLACE_WITH_RABBIT_PASS>");
        ProductionSecretValidator validator = new ProductionSecretValidator(validSecurityProperties(), env);

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.rabbitmq.ssl.enabled")
                .hasMessageContaining("spring.rabbitmq.password");
    }

    @Test
    @DisplayName("Should fail when Liquibase is enabled with missing or placeholder password")
    void validate_withInsecureLiquibasePassword_shouldThrow() {
        MockEnvironment env = validProdEnvironment()
                .withProperty("spring.liquibase.password", "changeme");
        ProductionSecretValidator validator = new ProductionSecretValidator(validSecurityProperties(), env);

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.liquibase.password");
    }

    @Test
    @DisplayName("Should succeed for services without datasource or Liquibase (e.g. ingestion-poller)")
    void validate_withoutDatasourceOrLiquibase_shouldSucceed() {
        MockEnvironment pollerEnv = new MockEnvironment()
                .withProperty("spring.rabbitmq.host", "rabbitmq.eve-system.svc.cluster.local")
                .withProperty("spring.rabbitmq.ssl.enabled", "true")
                .withProperty("spring.rabbitmq.password", PROD_RABBIT_PASSWORD);
        EveSecurityProperties pollerSecurity = new EveSecurityProperties(
                PROD_USERNAME,
                PROD_PASSWORD,
                "X-API-Key",
                List.of(PROD_ADMIN_KEY),
                List.of(PROD_ACTUATOR_KEY),
                null
        );
        ProductionSecretValidator validator = new ProductionSecretValidator(pollerSecurity, pollerEnv);

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    private static EveSecurityProperties validSecurityProperties() {
        return new EveSecurityProperties(
                PROD_USERNAME,
                PROD_PASSWORD,
                "X-API-Key",
                List.of(PROD_ADMIN_KEY),
                List.of(PROD_ACTUATOR_KEY),
                List.of(PROD_OPERATOR_KEY)
        );
    }

    private static MockEnvironment validProdEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url",
                        "jdbc:postgresql://postgres.eve-system.svc.cluster.local:5432/eve_bi?sslmode=require")
                .withProperty("spring.datasource.password", PROD_DB_PASSWORD)
                .withProperty("spring.liquibase.change-log", "classpath:/db/changelog/db.changelog-master.xml")
                .withProperty("spring.liquibase.enabled", "true")
                .withProperty("spring.liquibase.password", PROD_LIQUIBASE_PASSWORD)
                .withProperty("spring.rabbitmq.host", "rabbitmq.eve-system.svc.cluster.local")
                .withProperty("spring.rabbitmq.ssl.enabled", "true")
                .withProperty("spring.rabbitmq.password", PROD_RABBIT_PASSWORD);
    }
}
