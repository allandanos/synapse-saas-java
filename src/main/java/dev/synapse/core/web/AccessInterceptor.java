package dev.synapse.core.web;

import dev.synapse.core.context.TenantContext;
import dev.synapse.core.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs the handler's access annotations in the reference's dependency order:
 * tenant resolution first (bind RLS tenant, membership, suspension), then the
 * permission check. Errors propagate to the problem-document advice.
 */
@Component
public class AccessInterceptor implements HandlerInterceptor {

    private final TenantAccess tenants;
    private final PermissionChecks permissions;

    public AccessInterceptor(TenantAccess tenants, PermissionChecks permissions) {
        this.tenants = tenants;
        this.permissions = permissions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        PlatformAdminOnly platform = method.getMethodAnnotation(PlatformAdminOnly.class);
        if (platform != null) {
            tenants.requirePlatformAdmin(Principals.current());
        }
        RequirePermission permission = method.getMethodAnnotation(RequirePermission.class);
        RequireTenant tenant = method.getMethodAnnotation(RequireTenant.class);
        if (permission != null || tenant != null) {
            Principal principal = Principals.current();
            TenantContext resolved = tenants.resolve(request, principal);
            if (permission != null) {
                permissions.require(permission.value(), principal, resolved);
            }
        }
        return true;
    }
}
