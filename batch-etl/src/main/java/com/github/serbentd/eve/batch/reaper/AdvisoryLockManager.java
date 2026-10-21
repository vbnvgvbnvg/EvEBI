package com.github.serbentd.eve.batch.reaper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages database session-level advisory locks.
 * In PostgreSQL, uses native {@code pg_try_advisory_lock} and {@code pg_advisory_unlock}
 * bound to a dedicated physical database connection session.
 * In non-PostgreSQL environments (e.g. in-memory H2 during testing), provides simulated
 * in-memory lock tracking for deterministic testing.
 */
@Component
@Slf4j
public class AdvisoryLockManager implements DisposableBean {

    private final DataSource dataSource;
    private final boolean isPostgres;
    private final Map<Long, Connection> heldConnections = new ConcurrentHashMap<>();
    private final Set<Long> simulatedLocks = ConcurrentHashMap.newKeySet();

    public AdvisoryLockManager(DataSource dataSource) {
        this.dataSource = dataSource;
        this.isPostgres = detectPostgres(dataSource);
        log.info("AdvisoryLockManager initialized (PostgreSQL dialect: {})", isPostgres);
    }

    /**
     * Attempts to acquire an advisory lock for the given lock ID.
     * In PostgreSQL mode, acquires and pins a dedicated physical database connection
     * to keep the session advisory lock active for the job execution lifecycle.
     *
     * @param lockId unique lock identifier
     * @return true if acquired, false if held by another live database session
     */
    public boolean tryLock(long lockId) {
        if (isPostgres) {
            try {
                Connection conn = dataSource.getConnection();
                try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                    ps.setLong(1, lockId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getBoolean(1)) {
                            Connection previous = heldConnections.put(lockId, conn);
                            if (previous != null) {
                                closeQuietly(previous);
                            }
                            log.debug("Acquired PostgreSQL advisory lock {} on dedicated connection", lockId);
                            return true;
                        }
                    }
                }
                closeQuietly(conn);
                return false;
            } catch (SQLException e) {
                log.error("Failed to execute pg_try_advisory_lock for lockId: {}", lockId, e);
                return false;
            }
        } else {
            return simulatedLocks.add(lockId);
        }
    }

    /**
     * Releases an advisory lock for the given lock ID.
     * In PostgreSQL mode, unlocks and closes the pinned database connection back to the pool.
     *
     * @param lockId unique lock identifier
     * @return true if released
     */
    public boolean unlock(long lockId) {
        if (isPostgres) {
            Connection conn = heldConnections.remove(lockId);
            if (conn != null) {
                try {
                    try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                        ps.setLong(1, lockId);
                        try (ResultSet rs = ps.executeQuery()) {
                            boolean released = rs.next() && rs.getBoolean(1);
                            log.debug("Released PostgreSQL advisory lock {}: {}", lockId, released);
                            return released;
                        }
                    }
                } catch (SQLException e) {
                    log.warn("Error executing pg_advisory_unlock for lockId: {}", lockId, e);
                    return false;
                } finally {
                    closeQuietly(conn);
                }
            }
            return false;
        } else {
            return simulatedLocks.remove(lockId);
        }
    }

    /**
     * For testing purposes in non-Postgres environments: simulates that another external node is holding a lock.
     */
    public void simulateLockHeld(long lockId) {
        simulatedLocks.add(lockId);
    }

    /**
     * For testing purposes in non-Postgres environments: clears simulated locks.
     */
    public void clearSimulatedLocks() {
        simulatedLocks.clear();
    }

    private boolean detectPostgres(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            String productName = conn.getMetaData().getDatabaseProductName();
            return productName != null && productName.toLowerCase().contains("postgres");
        } catch (SQLException e) {
            log.warn("Failed to detect database product name, defaulting to non-Postgres lock mode", e);
            return false;
        }
    }

    @Override
    public void destroy() {
        heldConnections.forEach((lockId, conn) -> {
            try {
                try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    ps.setLong(1, lockId);
                    ps.executeQuery();
                }
            } catch (Exception e) {
                log.debug("Exception while unlocking advisory lock {} on shutdown", lockId, e);
            } finally {
                closeQuietly(conn);
            }
        });
        heldConnections.clear();
    }

    private void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException e) {
                log.warn("Error closing advisory lock database connection", e);
            }
        }
    }
}
