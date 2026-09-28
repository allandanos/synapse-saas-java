package dev.synapse.entitlements;

import dev.synapse.core.context.TenantContext;
import dev.synapse.core.web.RequireTenant;
import dev.synapse.entitlements.dto.EffectiveEntitlementsRead;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /v1/entitlements}: the tenant's effective feature/limit set (membership only, no permission). */
@RestController
public class EntitlementController {

    private final EntitlementService entitlements;

    public EntitlementController(EntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    @GetMapping("/v1/entitlements")
    @RequireTenant
    public EffectiveEntitlementsRead effective(TenantContext tenant) {
        return EffectiveEntitlementsRead.from(entitlements.effectiveForOrg(tenant.organizationId()));
    }
}
