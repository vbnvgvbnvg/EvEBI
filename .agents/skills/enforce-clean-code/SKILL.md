---
name: enforce-clean-code
description: Run compilation checks, code generation verification, test suite execution, and clean code hygiene audits on modified Java files.
---

# Intent
Use this skill after creating or modifying any Java class, `pom.xml`, Liquibase changelog, or application property file to ensure all changes pass strict architectural standards, type checking, and compilation before concluding your response.

# Verification Pipeline

### Gate 1: Code Review & Hygiene Audit (Self-Review Checklist)
Perform this audit first. If any rule is violated, refactor the code before running Maven commands:
- [ ] **No Field Injection:** Verify all dependencies are injected via constructor (`final` fields). No `@Autowired` on fields.
- [ ] **Immutable Records:** Verify all RabbitMQ message payloads, DTOs, and event structures use Java `record` instead of mutable POJOs.
- [ ] **No Magic Values:** Verify that queue names, region IDs, cron schedules, and thresholds are sourced from configuration properties or named constants.
- [ ] **Transaction Scope:** Verify network calls (HTTP/AMQP) are NOT enclosed inside `@Transactional` database methods.
- [ ] **Jakarta Imports:** Confirm no deprecated `javax.*` packages are imported.
- [ ] **Logging Hygiene:** Verify SLF4J is used with parameter syntax (`{}`). Ensure no `System.out` or `printStackTrace()` exists.
- [ ] **Liquibase Pair:** If a new JPA `@Entity` or field is created, verify a corresponding Liquibase changeset file has been written.

### Gate 2: Code Generation & Compilation
- Compiles parent and all child modules, verifying OpenAPI generation and QueryDSL Q-class annotation processing:
```bash
# macOS / Linux:
./mvnw clean compile -DskipTests

# Windows 11:
mvnw.cmd clean compile -DskipTests
```
- Verify zero compilation errors across all modules.

### Gate 3: Test Suite Execution
- Run unit and integration tests across the project:
```bash
# macOS / Linux:
./mvnw test

# Windows 11:
mvnw.cmd test
```
- Verify all tests pass with zero failures and zero errors.

### Gate 4: SonarCloud Quality Gate Verification
- When `SONAR_TOKEN` is configured, execute Sonar analysis with synchronous Quality Gate evaluation:
```bash
# macOS / Linux:
./mvnw clean verify sonar:sonar

# Windows 11:
mvnw.cmd clean verify sonar:sonar
```
- Verify the scanner finishes with `QUALITY GATE STATUS: PASSED` and `BUILD SUCCESS`.

### Gate 5: Git Status & Resource Check
- Run `git status` to verify:
  - No unintended temporary files, IDE folders (`.idea`), or build artifacts (`target/`) are unstaged.
  - No unexpected formatting or CRLF line-ending mutations were introduced (LF only).

# Exit Criteria
- Self-review checklist passes completely.
- Code generation & compilation exits with `BUILD SUCCESS`.
- Test suite exits with `BUILD SUCCESS`.
- SonarCloud Quality Gate passes (`QUALITY GATE STATUS: PASSED`).
- No compilation warnings or broken multi-module references remain.