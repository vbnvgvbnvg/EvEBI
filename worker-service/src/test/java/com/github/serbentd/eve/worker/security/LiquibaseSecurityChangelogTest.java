package com.github.serbentd.eve.worker.security;

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

@DisplayName("Worker Service Liquibase Security Changelog Validation")
class LiquibaseSecurityChangelogTest {

    private static final String MASTER_CHANGELOG = "src/main/resources/db/changelog/db.changelog-master.xml";
    private static final String SECURITY_CHANGELOG = "src/main/resources/db/changelog/security/01-configure-least-privilege-roles.xml";

    @Test
    @DisplayName("Master changelog must include security least-privilege changelog")
    void masterChangelog_shouldIncludeSecurityChangelog() throws Exception {
        Path masterPath = resolvePath(MASTER_CHANGELOG);
        String content = Files.readString(masterPath);

        assertThat(content)
                .contains("db/changelog/security/01-configure-least-privilege-roles.xml");
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
    @DisplayName("Security changelog must configure least-privilege roles and staging boundaries")
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
        assertThat(sql).contains("REVOKE CREATE ON SCHEMA staging FROM PUBLIC");
        assertThat(sql).contains("REVOKE ALL ON SCHEMA liquibase FROM PUBLIC, worker_dml, batch_dml, powerbi_reader");

        // liquibase_ddl migration admin
        assertThat(sql).contains("GRANT USAGE, CREATE ON SCHEMA liquibase, staging TO liquibase_ddl");
        assertThat(sql).contains("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA liquibase, staging TO liquibase_ddl");

        // worker_dml operational boundaries
        assertThat(sql).contains("GRANT USAGE ON SCHEMA staging TO worker_dml");
        assertThat(sql).contains("GRANT SELECT, INSERT, UPDATE ON TABLE staging.market_orders TO worker_dml");
        assertThat(sql).contains("REVOKE DELETE, TRUNCATE ON TABLE staging.market_orders FROM worker_dml");
        assertThat(sql).contains("REVOKE CREATE ON SCHEMA staging FROM worker_dml");

        // batch_dml read-only on staging
        assertThat(sql).contains("GRANT USAGE ON SCHEMA staging TO batch_dml");
        assertThat(sql).contains("GRANT SELECT ON TABLE staging.market_orders TO batch_dml");
        assertThat(sql).contains("REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE staging.market_orders FROM batch_dml");

        // powerbi_reader isolation
        assertThat(sql).contains("REVOKE ALL ON SCHEMA staging FROM powerbi_reader");
        assertThat(sql).contains("REVOKE ALL ON TABLE staging.market_orders FROM powerbi_reader");
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
        Path modulePath = Path.of("worker-service", relativePath);
        if (Files.exists(modulePath)) {
            return modulePath;
        }
        throw new IllegalArgumentException("Unable to resolve path: " + relativePath);
    }
}
