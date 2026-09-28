package dev.synapse.core.db;

import dev.synapse.core.config.SynapseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Fail fast when RLS would be a lie or a lockout (reference: {@code core/db.py:assert_role_matches_isolation}).
 *
 * <ul>
 *   <li>{@code app_and_rls} + a superuser / BYPASSRLS / table-owner role ⇒ policies never apply;
 *       the deployment believes it has defence-in-depth and does not.</li>
 *   <li>{@code app} + a role that is subject to policies ⇒ every tenant query returns zero rows
 *       because no GUC is ever set.</li>
 * </ul>
 * A mismatch always refuses to start; any other database error only does so in production.
 */
@Component
@Order(0)
public class RoleIsolationCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RoleIsolationCheck.class);

    public static final class RoleIsolationMismatchException extends IllegalStateException {
        RoleIsolationMismatchException(String message) {
            super(message);
        }
    }

    record RolePosture(boolean superuser, boolean bypassRls, boolean ownsTables, String roleName) {
        boolean bypasses() {
            return superuser || bypassRls || ownsTables;
        }
    }

    private final JdbcClient jdbc;
    private final SynapseProperties props;

    public RoleIsolationCheck(JdbcClient jdbc, SynapseProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        RolePosture posture;
        try {
            posture = jdbc.sql("""
                    SELECT r.rolsuper, r.rolbypassrls,
                           COALESCE((SELECT bool_and(t.tableowner = current_user) FROM pg_tables t WHERE t.schemaname = 'public'), true) AS owns_tables,
                           current_user AS role_name
                    FROM pg_roles r WHERE r.rolname = current_user
                    """)
                .query((rs, i) -> new RolePosture(rs.getBoolean("rolsuper"), rs.getBoolean("rolbypassrls"),
                    rs.getBoolean("owns_tables"), rs.getString("role_name")))
                .single();
        } catch (RuntimeException e) {
            log.error("db_role_isolation_check_failed error={}", e.toString());
            if (props.isProduction()) {
                throw e;
            }
            return;
        }
        assertMatches(posture, props.rlsEnabled());
        log.info("db_role_isolation_ok role={} tenant_isolation={}", posture.roleName(), props.tenantIsolation());
    }

    static void assertMatches(RolePosture posture, boolean rlsEnabled) {
        if (rlsEnabled && posture.bypasses()) {
            throw new RoleIsolationMismatchException("SYNAPSE_TENANT_ISOLATION=app_and_rls but DB role '" + posture.roleName()
                + "' bypasses RLS (superuser, BYPASSRLS, or table owner). Connect the API as a subject role (LOGIN NOBYPASSRLS NOINHERIT).");
        }
        if (!rlsEnabled && !posture.bypasses()) {
            throw new RoleIsolationMismatchException("SYNAPSE_TENANT_ISOLATION=app but DB role '" + posture.roleName()
                + "' is subject to RLS policies; every tenant query would return zero rows. Set app_and_rls or connect as the owner.");
        }
    }
}
