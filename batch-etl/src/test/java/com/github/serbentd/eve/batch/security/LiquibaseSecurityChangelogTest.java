package com.github.serbentd.eve.batch.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Batch ETL Liquibase Security Changelog Validation")
class LiquibaseSecurityChangelogTest {

    private static final String MASTER_CHANGELOG = "src/main/resources/db/changelog/db.changelog-master.xml";
    private static final String SECURITY_CHANGELOG = "src/main/resources/db/changelog/security/01-configure-least-privilege-roles.xml";
    private static final String BATCH_6_MIGRATION_CHANGELOG = "src/main/resources/db/changelog/batch/02-migrate-spring-batch-6-metadata.xml";

    @Test
    @DisplayName("Master changelog must include security least-privilege changelog")
    void masterChangelog_shouldIncludeSecurityChangelog() throws Exception {
        Path masterPath = resolvePath(MASTER_CHANGELOG);
        String content = Files.readString(masterPath);

        assertThat(content)
                .contains("db/changelog/security/01-configure-least-privilege-roles.xml");
    }

    @Test
    @DisplayName("Master changelog and Spring Batch 6 sequence migration changelog must configure both PostgreSQL and H2")
    void batch6MetadataMigrationChangelog_shouldCoverPostgreSqlAndH2() throws Exception {
        String masterContent = Files.readString(resolvePath(MASTER_CHANGELOG));
        assertThat(masterContent)
                .contains("db/changelog/batch/01-create-spring-batch-metadata.xml")
                .contains("db/changelog/batch/02-migrate-spring-batch-6-metadata.xml");
        assertThat(masterContent.indexOf("01-create-spring-batch-metadata.xml"))
                .isLessThan(masterContent.indexOf("02-migrate-spring-batch-6-metadata.xml"));

        Path migrationPath = resolvePath(BATCH_6_MIGRATION_CHANGELOG);
        Document doc = parseXml(migrationPath);
        NodeList changeSets = doc.getElementsByTagName("changeSet");
        assertThat(changeSets.getLength()).isEqualTo(2);

        Element pgChangeSet = (Element) changeSets.item(0);
        assertThat(pgChangeSet.getAttribute("dbms")).isEqualTo("postgresql");

        Element h2ChangeSet = (Element) changeSets.item(1);
        assertThat(h2ChangeSet.getAttribute("dbms")).isEqualTo("h2");

        String migrationSql = Files.readString(migrationPath);
        assertThat(migrationSql)
                .contains("ALTER SEQUENCE batch.BATCH_JOB_SEQ RENAME TO BATCH_JOB_INSTANCE_SEQ")
                .contains("createSequence sequenceName=\"BATCH_JOB_INSTANCE_SEQ\" schemaName=\"batch\"")
                .contains("dropSequence sequenceName=\"BATCH_JOB_SEQ\" schemaName=\"batch\"");
    }

    @Test
    @DisplayName("All security changesets must be scoped to PostgreSQL dbms to preserve H2 test compatibility")
    void securityChangesets_mustSpecifyPostgreSqlDbms() throws Exception {
        Document doc = parseXml(resolvePath(SECURITY_CHANGELOG));
        NodeList changeSets = doc.getElementsByTagName("changeSet");

        assertThat(changeSets.getLength()).isGreaterThanOrEqualTo(2);

        for (int i = 0; i < changeSets.getLength(); i++) {
            Element changeSet = (Element) changeSets.item(i);
            assertThat(changeSet.getAttribute("dbms"))
                    .withFailMessage("ChangeSet %s must specify dbms='postgresql'", changeSet.getAttribute("id"))
                    .isEqualTo("postgresql");
        }
    }

    @Test
    @DisplayName("Security changelog must configure least-privilege roles across staging, analytics, and batch")
    void securityChangelog_shouldEnforceLeastPrivilegeInvariants() throws Exception {
        String sql = Files.readString(resolvePath(SECURITY_CHANGELOG));

        // Roles & isolated liquibase schema declaration
        assertThat(sql).contains("CREATE ROLE liquibase_ddl");
        assertThat(sql).contains("CREATE ROLE worker_dml");
        assertThat(sql).contains("CREATE ROLE batch_dml");
        assertThat(sql).contains("CREATE ROLE powerbi_reader");
        assertThat(sql).contains("CREATE SCHEMA IF NOT EXISTS liquibase");

        // Public schema lockdown & liquibase schema isolation
        assertThat(sql).contains("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        assertThat(sql).contains("REVOKE CREATE ON SCHEMA staging, analytics, batch FROM PUBLIC");
        assertThat(sql).contains("REVOKE ALL ON SCHEMA liquibase FROM PUBLIC, worker_dml, batch_dml, powerbi_reader");

        // liquibase_ddl migration admin
        assertThat(sql).contains("GRANT USAGE, CREATE ON SCHEMA liquibase, staging, analytics, batch TO liquibase_ddl");
        assertThat(sql).contains("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA liquibase, staging, analytics, batch TO liquibase_ddl");

        // worker_dml strictly isolated to staging (denied analytics & batch)
        assertThat(sql).contains("REVOKE ALL ON SCHEMA analytics, batch FROM worker_dml");
        assertThat(sql).contains("GRANT USAGE ON SCHEMA staging TO worker_dml");
        assertThat(sql).contains("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA staging TO worker_dml");
        assertThat(sql).contains("REVOKE DELETE, TRUNCATE ON ALL TABLES IN SCHEMA staging FROM worker_dml");

        // batch_dml operational boundaries (SELECT on staging, DML on analytics and batch)
        assertThat(sql).contains("GRANT USAGE ON SCHEMA staging, analytics, batch TO batch_dml");
        assertThat(sql).contains("GRANT SELECT ON ALL TABLES IN SCHEMA staging TO batch_dml");
        assertThat(sql).contains("REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA staging FROM batch_dml");
        assertThat(sql).contains("REVOKE CREATE ON SCHEMA staging, analytics, batch FROM batch_dml");
        assertThat(sql).contains("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA analytics TO batch_dml");
        assertThat(sql).contains("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA batch TO batch_dml");

        // powerbi_reader isolation: analytical SELECT only, zero staging/batch/metadata access
        assertThat(sql).contains("REVOKE ALL ON SCHEMA staging, batch FROM powerbi_reader");
        assertThat(sql).contains("GRANT USAGE ON SCHEMA analytics TO powerbi_reader");
        assertThat(sql).contains("fact_market_daily_summary");
        assertThat(sql).contains("fact_order_book_liquidity");
        assertThat(sql).contains("REVOKE ALL ON TABLE analytics.sde_metadata FROM powerbi_reader");
        assertThat(sql).contains("REVOKE ALL ON ALL SEQUENCES IN SCHEMA analytics FROM powerbi_reader");
        assertThat(sql).doesNotContain("GRANT SELECT ON TABLES TO powerbi_reader");
    }

    private Document parseXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (InputStream is = Files.newInputStream(path)) {
            return builder.parse(is);
        }
    }

    private Path resolvePath(String relativePath) {
        Path direct = Path.of(relativePath);
        if (Files.exists(direct)) {
            return direct;
        }
        Path modulePath = Path.of("batch-etl", relativePath);
        if (Files.exists(modulePath)) {
            return modulePath;
        }
        throw new IllegalArgumentException("Unable to resolve path: " + relativePath);
    }
}
