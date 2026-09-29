package dev.synapse.worker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.IntSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cross-process job coordination without a new table: every tick takes
 * {@code pg_try_advisory_lock(hashtext('job:<name>'))} on its OWN connection
 * and skips when another worker already holds it. Rows are then claimed with
 * {@code FOR UPDATE SKIP LOCKED}, so overlapping ticks could never double-send
 * anyway — this only stops N workers from doing the same scan.
 */
@Component
public class AdvisoryLock {

    private static final Logger log = LoggerFactory.getLogger(AdvisoryLock.class);
    public static final String KEY_PREFIX = "job:";

    private final DataSource dataSource;

    public AdvisoryLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Runs {@code body} while holding the lock; returns -1 when the lock was held elsewhere. */
    public int runExclusively(String job, IntSupplier body) {
        String key = KEY_PREFIX + job;
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection, key)) {
                log.debug("job_skipped_locked job={}", job);
                return -1;
            }
            try {
                return body.getAsInt();
            } finally {
                unlock(connection, key);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not take the advisory lock for " + job, e);
        }
    }

    private static boolean tryLock(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
            statement.setString(1, key);
            statement.executeQuery().close();
        }
    }
}
