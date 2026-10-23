package com.github.serbentd.eve.batch.job.sde;

import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Database dialect resolved once at writer startup, used to bind the native single-pass
 * upsert syntax of the active database (PostgreSQL in production, H2 in Docker-free tests).
 */
@Slf4j
enum SqlDialect {

    POSTGRESQL,
    H2;

    private static final String POSTGRES_PRODUCT_MARKER = "postgres";

    /**
     * Detects the dialect from JDBC metadata. Falls back to {@link #H2} when detection fails,
     * which surfaces as an immediate SQL syntax failure on PostgreSQL rather than silent data corruption.
     */
    static SqlDialect detect(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            String productName = conn.getMetaData().getDatabaseProductName();
            boolean isPostgres = productName != null
                    && productName.toLowerCase(Locale.ROOT).contains(POSTGRES_PRODUCT_MARKER);
            return isPostgres ? POSTGRESQL : H2;
        } catch (SQLException e) {
            log.warn("Failed to detect database product name, falling back to H2 merge syntax", e);
            return H2;
        }
    }
}
