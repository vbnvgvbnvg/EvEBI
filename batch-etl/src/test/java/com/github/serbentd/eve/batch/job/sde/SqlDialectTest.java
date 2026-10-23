package com.github.serbentd.eve.batch.job.sde;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqlDialectTest {

    private final DataSource dataSource;
    private final Connection connection;
    private final DatabaseMetaData metaData;

    SqlDialectTest(@Mock DataSource dataSource, @Mock Connection connection, @Mock DatabaseMetaData metaData) {
        this.dataSource = dataSource;
        this.connection = connection;
        this.metaData = metaData;
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "PostgreSQL, POSTGRESQL",
            "postgresql, POSTGRESQL",
            "H2, H2"
    })
    @DisplayName("Should resolve dialect from JDBC product name and release the probe connection")
    void detect_shouldResolveFromProductName(String productName, SqlDialect expected) throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn(productName);

        assertThat(SqlDialect.detect(dataSource)).isEqualTo(expected);
        verify(connection).close();
    }

    @Test
    @DisplayName("Should fall back to H2 when the product name is unavailable")
    void detect_whenProductNameNull_shouldFallBackToH2() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn(null);

        assertThat(SqlDialect.detect(dataSource)).isEqualTo(SqlDialect.H2);
    }

    @Test
    @DisplayName("Should fall back to H2 when metadata detection throws SQLException")
    void detect_whenConnectionFails_shouldFallBackToH2() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused"));

        assertThat(SqlDialect.detect(dataSource)).isEqualTo(SqlDialect.H2);
    }
}
