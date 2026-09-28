package dev.synapse.core.context;

import dev.synapse.core.errors.TenantContextMissingError;

/**
 * Thread-bound access to the current {@link RequestContext} — the single source
 * of "who is acting, in which org" below the controllers (the reference's
 * contextvars). Opened by {@link RequestScopeFilter}; background work must
 * open its own scope explicitly.
 */
public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private RequestContextHolder() {}

    public static RequestContext open(String requestId) {
        RequestContext ctx = new RequestContext(requestId);
        CURRENT.set(ctx);
        return ctx;
    }

    /** The current context or {@code null} outside a request scope. */
    public static RequestContext get() {
        return CURRENT.get();
    }

    public static RequestContext require() {
        RequestContext ctx = CURRENT.get();
        if (ctx == null) {
            throw new IllegalStateException("No request context is open on this thread");
        }
        return ctx;
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String requestId() {
        RequestContext ctx = CURRENT.get();
        return ctx == null ? null : ctx.requestId();
    }

    public static UserContext currentUser() {
        RequestContext ctx = CURRENT.get();
        return ctx == null ? null : ctx.user();
    }

    public static UserContext requireUser() {
        UserContext user = currentUser();
        if (user == null) {
            throw new TenantContextMissingError("No user context is active for this operation");
        }
        return user;
    }

    public static TenantContext currentTenant() {
        RequestContext ctx = CURRENT.get();
        return ctx == null ? null : ctx.tenant();
    }

    public static TenantContext requireTenant() {
        TenantContext tenant = currentTenant();
        if (tenant == null) {
            throw new TenantContextMissingError("No tenant context is active for this operation");
        }
        return tenant;
    }
}
