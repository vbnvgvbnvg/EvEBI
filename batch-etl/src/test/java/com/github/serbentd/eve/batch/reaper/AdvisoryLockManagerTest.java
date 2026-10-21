package com.github.serbentd.eve.batch.reaper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AdvisoryLockManagerTest {

    @Test
    @DisplayName("Should detect PostgreSQL database product name correctly")
    void shouldDetectPostgres() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection detectConnection = mock(Connection.class);
        Connection lockConnection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(detectConnection, lockConnection);
        when(detectConnection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");

        AdvisoryLockManager manager = new AdvisoryLockManager(dataSource);

        // Verify tryLock uses Postgres prepared statement
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(lockConnection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(true);

        boolean locked = manager.tryLock(42L);
        assertThat(locked).isTrue();

        PreparedStatement unlockPs = mock(PreparedStatement.class);
        ResultSet unlockRs = mock(ResultSet.class);
        when(lockConnection.prepareStatement(contains("pg_advisory_unlock"))).thenReturn(unlockPs);
        when(unlockPs.executeQuery()).thenReturn(unlockRs);
        when(unlockRs.next()).thenReturn(true);
        when(unlockRs.getBoolean(1)).thenReturn(true);

        boolean unlocked = manager.unlock(42L);
        assertThat(unlocked).isTrue();
        verify(lockConnection).close();
    }

    @Test
    @DisplayName("Should handle SQLException gracefully when acquiring lock in PostgreSQL mode")
    void shouldHandleSqlExceptionInTryLock() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection detectConnection = mock(Connection.class);
        Connection lockConnection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(detectConnection, lockConnection);
        when(detectConnection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");

        AdvisoryLockManager manager = new AdvisoryLockManager(dataSource);

        when(lockConnection.prepareStatement(anyString())).thenThrow(new SQLException("Network error"));

        boolean locked = manager.tryLock(99L);
        assertThat(locked).isFalse();
    }

    @Test
    @DisplayName("Should handle lock acquisition failure in PostgreSQL mode and close connection")
    void shouldCloseConnectionWhenLockNotAcquired() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection detectConnection = mock(Connection.class);
        Connection lockConnection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(detectConnection, lockConnection);
        when(detectConnection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");

        AdvisoryLockManager manager = new AdvisoryLockManager(dataSource);

        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(lockConnection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(false);

        boolean locked = manager.tryLock(100L);
        assertThat(locked).isFalse();
        verify(lockConnection).close();
    }

    @Test
    @DisplayName("Should release and close all held connections on destroy")
    void shouldReleaseAllConnectionsOnDestroy() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection detectConnection = mock(Connection.class);
        Connection lockConnection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(detectConnection, lockConnection);
        when(detectConnection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");

        AdvisoryLockManager manager = new AdvisoryLockManager(dataSource);

        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(lockConnection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(true);

        manager.tryLock(200L);
        manager.destroy();

        verify(lockConnection, atLeastOnce()).close();
    }

    @Test
    @DisplayName("Should fall back to simulated mode when database is non-PostgreSQL")
    void shouldFallbackToSimulatedMode() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("H2");

        AdvisoryLockManager manager = new AdvisoryLockManager(dataSource);

        assertThat(manager.tryLock(10L)).isTrue();
        assertThat(manager.tryLock(10L)).isFalse();

        manager.simulateLockHeld(20L);
        assertThat(manager.tryLock(20L)).isFalse();

        assertThat(manager.unlock(10L)).isTrue();
        assertThat(manager.tryLock(10L)).isTrue();

        manager.clearSimulatedLocks();
        assertThat(manager.tryLock(20L)).isTrue();
    }
}
