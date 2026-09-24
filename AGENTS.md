# Project Rules & Standards

## Technology Stack
- **Language & Runtime:** Java 21 (LTS)
- **Framework:** Spring Boot 3.2.x (Spring 6, Jakarta EE namespace)
- **Build System:** Apache Maven (Multi-Module with Maven Wrapper)
- **Database & Migration:** PostgreSQL, Liquibase, QueryDSL 5.x
- **Messaging:** RabbitMQ (Spring AMQP)
- **API Client:** Spring WebFlux (`WebClient`), OpenAPI Generator 7.x
- **Resilience:** Resilience4j (Rate limiting, Circuit breaking, Retry)

## Cross-Platform Safeguards
- This is a cross-platform project developed across macOS and Windows 11.
- **Line Endings:** Enforce standard `LF` line endings across all files via `.gitattributes`. Never commit files with `CRLF`.
- **Paths:** Always use platform-independent paths (`java.nio.file.Path`, `File.separator`, or URI-based resource loaders). Never write hardcoded `/` or `\` in business logic.
- **CLI Commands:** Always invoke the local wrapper: `./mvnw` on macOS/Linux and `mvnw.cmd` on Windows.

## Project Organization & Module Boundaries

### Multi-Module Structure
- `esi-client`: OpenAPI-generated code and base reactive WebClient configuration. Does not depend on any sibling module.
- `ingestion-poller`: Scheduled polling services, rate limiting, and RabbitMQ message publishers. Depends only on `esi-client`.
- `worker-service`: RabbitMQ listeners, business validation, JPA entities, Liquibase changelogs, QueryDSL repositories, and bulk persistence.
- `batch-etl`: Spring Batch jobs for daily aggregations, rollups, and analytical star schema transformations.

### Package & File Naming Conventions
- **Base Package:** `com.github.serbentd.eve` (or matching project groupId).
- **Package by Feature/Layer:**
  - `config/`: Spring `@Configuration` classes.
  - `domain/` or `entity/`: JPA entities and database domain models.
  - `dto/` or `event/`: Immutable transit records (RabbitMQ payloads, internal DTOs).
  - `repository/`: Spring Data JPA and QueryDSL repository interfaces.
  - `service/`: Core transactional business logic.
  - `listener/` or `consumer/`: Spring AMQP RabbitMQ listener components.
  - `poller/` or `job/`: Scheduled ingestion polling tasks and batch jobs.
- **Class Suffixes by Role:**
  - `*Config`: Spring configuration classes (`RabbitMqConfig`, `WebClientConfig`).
  - `*Entity`: JPA database mapping entities (`MarketOrderEntity`).
  - `*Event` / `*Dto`: Immutable data-transfer records (`MarketOrderEvent`).
  - `*Repository`: QueryDSL and Spring Data interfaces (`MarketOrderRepository`).
  - `*CustomRepository` / `*CustomRepositoryImpl`: QueryDSL custom implementations.
  - `*Service`: Business logic components (`OrderIngestionService`).
  - `*Poller`: Scheduled external API fetchers (`MarketOrderPoller`).
  - `*Listener`: AMQP message consumers (`OrderPersistenceListener`).

## Architecture & Component Design

- **Strict Immutability with Java Records:** All message payloads, DTOs, query projections, and event envelopes MUST be Java `record` types. Never use mutable POJOs for transit data.
- **Explicit Dependency Injection:** Always prefer explicit constructor injection over field injection (`@Autowired` on fields is strictly prohibited). Use `@RequiredArgsConstructor` (if Lombok is enabled) or explicit `final` constructor parameters.
  - **Constructor Injection in Tests:** Test classes MUST also use constructor injection for Spring beans and Mockito mocks. Set `spring.test.constructor.autowire.mode = all` in `src/test/resources/junit-platform.properties`. Field injection via `@Autowired` in tests is strictly prohibited.
- **Docker-Free Default Testing:**
  - Default test execution via `./mvnw test` MUST execute completely in-memory without requiring Docker or OrbStack.
  - Use H2 in PostgreSQL mode (`MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH`) with Liquibase migrations enabled.
  - Disable automatic AMQP listener startup in tests (`spring.rabbitmq.listener.simple.auto-startup=false`) to prevent connection attempts to missing local brokers.
  - Heavy containerized tests (Testcontainers) must be isolated under a separate optional Maven profile or test category.
- **Container Health Probes (Liveness vs. Readiness):**
  - Liveness probe (`/actuator/health/liveness`) must strictly monitor internal JVM state (`livenessState`). Never fail liveness on external database or broker outages.
  - Readiness probe (`/actuator/health/readiness`) must verify downstream dependencies (`readinessState, db, rabbit`).
  - Services exposing HTTP health probes must include `spring-boot-starter-web` or `webflux` to bind the web server to `${SERVER_PORT}`.
- **Database Schema Management (Liquibase Only):**
  - Hibernate DDL auto-generation (`ddl-auto`) MUST be set to `validate` or `none` in all configurations.
  - All database schema changes (tables, indexes, constraints, views) must be managed exclusively through formatted Liquibase XML/YAML changelogs under `src/main/resources/db/changelog/`.
- **QueryDSL for Dynamic & Complex Queries:**
  - Standard CRUD operations should use Spring Data JPA repositories.
  - Dynamic filters, bulk filtering, analytical predicates, and joins must use generated QueryDSL Q-classes (`QOrderEntity`) via custom repository interfaces.
- **Resilient Polling Architecture:**
  - External calls to ESI must respect CCP's `X-ESI-Error-Limit-Remain` and `X-ESI-Error-Limit-Reset` response headers.
  - All external API calls must be wrapped in Resilience4j rate limiters and circuit breakers to prevent bans.
- **Transaction Boundaries:**
  - Annotate service-layer write methods with `@Transactional`.
  - Mark read-only methods with `@Transactional(readOnly = true)` to optimize database flush cycles and dirty-checking overhead.
  - Never execute network I/O (ESI HTTP requests, RabbitMQ calls) inside active database transactions.
- **Decoupled Event Publishing:** Ingestion pollers must only fetch, lightly validate, and publish messages to RabbitMQ. They must never perform database persistence directly.
- **AMQP Generic Collection Deserialization:**
  - When deserializing JSON arrays into generic collections (`List<T>`), always use `SmartMessageConverter` with `ParameterizedTypeReference<List<T>>` to prevent type erasure to `LinkedHashMap`.
- **Configuration Properties Best Practices (Schema in Java, Defaults in YAML):**
  - All `@ConfigurationProperties` classes MUST be modeled as immutable Java `record` types defining strictly the schema, types, and Jakarta EE validation constraints (`@NotNull`, `@NotBlank`, `@Positive`, `@NotEmpty`, `@Valid`).
  - **Zero Fallback Constants:** Never declare `DEFAULT_*` constants or write null-coalescing/fallback logic in constructor bodies inside `@ConfigurationProperties` records.
  - **Single Source of Truth:** All default values, intervals, thresholds, and fallback parameters MUST be declared exclusively in `application.yml` using environment variable fallback syntax (e.g. `${EVE_POLLER_FIXED_RATE:5m}`).
  - **Fail-Fast Validation:** Missing or out-of-range configuration must cause startup validation to fail immediately rather than being silently replaced by hardcoded defaults.

## Coding Style & Paradigms

- **Java 21 Idioms:** Use modern Java features: pattern matching for `instanceof`, `switch` expressions, text blocks (`"""`) for complex SQL/JSON, and the Stream API.
- **Eliminate Magic Numbers & Hardcoded Strings:**
  - All queue names, exchange names, routing keys, schedule intervals, and batch chunk sizes must be externalized to `@ConfigurationProperties` classes and configured in `application.yml`.
  - **Domain Entity IDs:** Game entity identifiers (e.g. EVE region IDs, type IDs, station IDs) MUST NOT be declared as constants in production code; they must be provided purely via external configuration or database dimension tables.
  - **Test Fixture Constants:** Descriptive named constants for domain IDs (e.g. `THE_FORGE_REGION_ID = 10000002L`, `JITA_4_4_STATION_ID = 60003760L`) are permitted ONLY within test fixtures and test classes to prevent raw magic numbers in test code.
- **Jakarta Namespace:** Always use `jakarta.*` packages (`jakarta.persistence.*`, `jakarta.validation.*`, `jakarta.annotation.*`). Never import `javax.*`.
- **Logging Standards:** Use SLF4J (`LoggerFactory` or `@Slf4j`).
  - Always use parameterized logging (`log.info("Processing order id: {}", orderId)`).
  - Never use string concatenation inside logging statements.
  - Never use `System.out.println()` or `e.printStackTrace()`. Always log exceptions with context: `log.error("Failed to ingest region orders", e)`.

## Security & Hardening Standards

- **Zero Hardcoded Secrets & Externalization:**
  - Never commit real passwords, API tokens, or private keys to version control.
  - All database passwords, broker credentials, and secret tokens MUST be externalized via environment variables. Fallback defaults in `application.yml` are permitted ONLY for local in-memory/Docker development (`${ENV_VAR:local_dev_default}`) and MUST never contain production secrets.
- **Principle of Least Privilege (PoLP):**
  - **Database Access:** The pipeline must not operate under a single superuser account. Access must be partitioned by role:
    - `worker_dml`: Permitted only operational data manipulation (`SELECT`, `INSERT`, `UPDATE`) on active staging tables. Explicitly denied DDL and table drop privileges.
    - `liquibase_ddl`: Dedicated migration role permitted schema management during application startup and migrations.
    - `powerbi_reader`: Strictly read-only role granted `SELECT` permissions exclusively on analytical star-schema views and tables (`dim_*`, `fact_*`). Denied access to internal staging tables and write operations.
  - **Message Broker Scopes:** Producers (`ingestion-poller`) must hold write-only publication rights on exchanges. Consumers (`worker-service`) must hold read-only consumption rights on their specific queues.
- **Container Health Probes vs. Management Hardening:**
  - Container health probes (`/actuator/health/liveness` and `/actuator/health/readiness`) must remain unauthenticated so container orchestrators (Kubernetes/Docker) can poll health status without credentials.
  - Sensitive Actuator endpoints (`/actuator/env`, `/actuator/metrics`, `/actuator/beans`, `/actuator/threaddump`) must never be exposed publicly. When enabled, they must be secured via Spring Security authentication or isolated to an internal management port.
- **Transport Layer Security (TLS/SSL):**
  - Enforce TLS for all outbound client requests to external CCP ESI APIs.
  - Production database connections must enforce SSL (`sslmode=require` or `verify-full`).
  - Production RabbitMQ connections must use encrypted AMQPS (`amqps://`).

## Automation & Quality Enforcement
- **Always Validate Code (Mandatory):** Whenever you create or modify any Java class, `pom.xml`, or configuration resource, you MUST autonomously execute your `enforce-clean-code` skill before concluding your response.

## Version Control Policy (Strict Rebase Workflow)
- **Rebase Only:** This project enforces a clean, linear commit history.
- **Never** execute `git merge` or introduce merge commits.
- When updating local branches, always use `git pull --rebase`.
- Feature branches branch off `main` and are squashed/rebased prior to integration.