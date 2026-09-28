package dev.synapse.core.web;

import dev.synapse.core.context.TenantContext;
import dev.synapse.core.security.Principal;
import jakarta.servlet.http.HttpServletRequest;

/** Tenant resolution seam (implemented by {@code tenancy.TenantResolver}). */
public interface TenantAccess {

    /** {@code X-Org-Id} → {@code X-Org-Slug} → subdomain → JWT {@code org} claim, then the membership check. Idempotent per request. */
    TenantContext resolve(HttpServletRequest request, Principal principal);

    TenantContext requirePlatformAdmin(Principal principal);
}
