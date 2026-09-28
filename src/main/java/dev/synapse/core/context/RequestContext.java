package dev.synapse.core.context;

import java.util.UUID;

/**
 * Per-request mutable holder: request id, the bound actor and tenant, and the
 * RLS bindings ({@code app.current_user}, {@code app.current_tenant},
 * {@code app.rls_platform}) to (re)apply at every transaction start.
 */
public final class RequestContext {

    private final String requestId;
    private UserContext user;
    private TenantContext tenant;
    private UUID rlsUser;
    private UUID rlsTenant;
    private boolean rlsPlatform;

    public RequestContext(String requestId) {
        this.requestId = requestId;
    }

    public String requestId() {
        return requestId;
    }

    public UserContext user() {
        return user;
    }

    public void setUser(UserContext user) {
        this.user = user;
    }

    public TenantContext tenant() {
        return tenant;
    }

    public void setTenant(TenantContext tenant) {
        this.tenant = tenant;
    }

    public UUID rlsUser() {
        return rlsUser;
    }

    public void setRlsUser(UUID rlsUser) {
        this.rlsUser = rlsUser;
    }

    public UUID rlsTenant() {
        return rlsTenant;
    }

    public void setRlsTenant(UUID rlsTenant) {
        this.rlsTenant = rlsTenant;
    }

    public boolean rlsPlatform() {
        return rlsPlatform;
    }

    public void setRlsPlatform(boolean rlsPlatform) {
        this.rlsPlatform = rlsPlatform;
    }
}
