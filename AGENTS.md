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

## Coding Style & Paradigms

- **Java 21 Idioms:** Use modern Java features: pattern matching for `instanceof`, `switch` expressions, text blocks (`"""`) for complex SQL/JSON, and the Stream API.
- **Eliminate Magic Numbers & Hardcoded Strings:**
  - All queue names, exchange names, routing keys, schedule intervals, EVE region IDs, and batch chunk sizes must be externalized to `@ConfigurationProperties` classes or declared as explicit constants.
- **Jakarta Namespace:** Always use `jakarta.*` packages (`jakarta.persistence.*`, `jakarta.validation.*`, `jakarta.annotation.*`). Never import `javax.*`.
- **Logging Standards:** Use SLF4J (`LoggerFactory` or `@Slf4j`).
  - Always use parameterized logging (`log.info("Processing order id: {}", orderId)`).
  - Never use string concatenation inside logging statements.
  - Never use `System.out.println()` or `e.printStackTrace()`. Always log exceptions with context: `log.error("Failed to ingest region orders", e)`.

## Automation & Quality Enforcement
- **Always Validate Code (Mandatory):** Whenever you create or modify any Java class, `pom.xml`, or configuration resource, you MUST autonomously execute your `enforce-clean-code` skill before concluding your response.

## Version Control Policy (Strict Rebase Workflow)
- **Rebase Only:** This project enforces a clean, linear commit history.
- **Never** execute `git merge` or introduce merge commits.
- When updating local branches, always use `git pull --rebase`.
- Feature branches branch off `main` and are squashed/rebased prior to integration.