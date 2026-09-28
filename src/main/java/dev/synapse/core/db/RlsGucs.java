package dev.synapse.core.db;

import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.context.RequestContext;
import dev.synapse.core.context.RequestContextHolder;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Row-level-security GUCs (reference: {@code core/db.py}).
 *
 * <p>Policies admit a row when {@code organization_id = app.current_tenant},
 * {@code app.rls_platform = 'on'}, or {@code user_id = app.current_user}. All
 * three are transaction-local ({@code set_config(..., true)}) so pooled
 * connections never leak a tenant. Bindings live on the request context and are
 * re-applied by {@link RlsTransactionManager} at every transaction start; when a
 * binding happens mid-transaction (create org, accept invite, tenant resolution
 * before the membership query) it is applied to that transaction immediately.
 * Every method is a no-op unless {@code SYNAPSE_TENANT_ISOLATION=app_and_rls}.
 */
@Component
public class RlsGucs {

    public static final String CURRENT_USER = "app.current_user";
    public static final String CURRENT_TENANT = "app.current_tenant";
    public static final String PLATFORM = "app.rls_platform";

    private final JdbcClient jdbc;
    private final boolean enabled;

    public RlsGucs(JdbcClient jdbc, SynapseProperties props) {
        this.jdbc = jdbc;
        this.enabled = props.rlsEnabled();
    }

    public boolean enabled() {
        return enabled;
    }

    /** Bind the authenticated user so their own memberships are readable pre-tenant. */
    public void bindUser(UUID userId) {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx != null) {
            ctx.setRlsUser(userId);
        }
        applyNow(CURRENT_USER, userId.toString());
    }

    /** Bind the request transaction to one tenant. Call before the first tenant-scoped query. */
    public void bindTenant(UUID organizationId) {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx != null) {
            ctx.setRlsTenant(organizationId);
        }
        applyNow(CURRENT_TENANT, organizationId.toString());
    }

    /** Platform-admin scope: policies admit every row for this transaction. */
    public void bindPlatform() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx != null) {
            ctx.setRlsPlatform(true);
        }
        applyNow(PLATFORM, "on");
    }

    /** Called by the transaction manager right after {@code BEGIN}: replay the request's bindings. */
    void applyToCurrentTransaction() {
        if (!enabled) {
            return;
        }
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null) {
            return;
        }
        if (ctx.rlsUser() != null) {
            set(CURRENT_USER, ctx.rlsUser().toString());
        }
        if (ctx.rlsTenant() != null) {
            set(CURRENT_TENANT, ctx.rlsTenant().toString());
        }
        if (ctx.rlsPlatform()) {
            set(PLATFORM, "on");
        }
    }

    private void applyNow(String name, String value) {
        if (enabled && TransactionSynchronizationManager.isActualTransactionActive()) {
            set(name, value);
        }
    }

    private void set(String name, String value) {
        jdbc.sql("SELECT set_config(:name, :value, true)")
            .param("name", name)
            .param("value", value)
            .query(String.class)
            .single();
    }
}
