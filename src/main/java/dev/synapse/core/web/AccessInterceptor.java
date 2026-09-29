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
 * permission check, then the feature gate. Errors propagate to the problem-document advice.
 */
@Component
public class AccessInterceptor implements HandlerInterceptor {

    private final TenantAccess tenants;
    private final PermissionChecks permissions;
    private final FeatureChecks features;
    private final FlagChecks flags;

    public AccessInterceptor(TenantAccess tenants, PermissionChecks permissions, FeatureChecks features, FlagChecks flags) {
        this.tenants = tenants;
        this.permissions = permissions;
        this.features = features;
        this.flags = flags;
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
        // A router-level gate (the reference's `APIRouter(dependencies=[...])`) is declared
        // once on the controller; a method annotation still wins when both are present.
        RequireFeature feature = method.getMethodAnnotation(RequireFeature.class);
        if (feature == null) {
            feature = method.getBeanType().getAnnotation(RequireFeature.class);
        }
        RequireFlag flag = method.getMethodAnnotation(RequireFlag.class);
        if (flag == null) {
            flag = method.getBeanType().getAnnotation(RequireFlag.class);
        }
        if (permission != null || tenant != null || feature != null || flag != null) {
            Principal principal = Principals.current();
            TenantContext resolved = tenants.resolve(request, principal);
            if (permission != null) {
                permissions.require(permission.value(), principal, resolved);
            }
            if (feature != null) {
                features.require(resolved.organizationId(), feature.value());
            }
            if (flag != null) {
                flags.require(flag.value(), resolved.organizationId(), principal.id());
            }
        }
        return true;
    }
}
