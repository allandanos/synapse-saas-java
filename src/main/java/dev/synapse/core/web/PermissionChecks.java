package dev.synapse.core.web;

import dev.synapse.core.context.TenantContext;
import dev.synapse.core.security.Principal;

/** Permission gate seam (implemented by {@code authorization.PermissionGuard}). */
public interface PermissionChecks {

    void require(String permission, Principal principal, TenantContext tenant);
}
