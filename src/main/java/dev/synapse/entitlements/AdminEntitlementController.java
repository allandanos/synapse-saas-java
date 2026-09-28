package dev.synapse.entitlements;

import dev.synapse.core.errors.EntitlementNotFoundError;
import dev.synapse.core.security.Principal;
import dev.synapse.core.web.PlatformAdminOnly;
import dev.synapse.entitlements.dto.EffectiveEntitlementsRead;
import dev.synapse.entitlements.dto.GrantCreated;
import dev.synapse.entitlements.dto.GrantRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator surface (ADR 0008): grants are a platform action, never a tenant one.
 * A tenant holding {@code entitlement:manage} could grant itself {@code sso} or
 * raise its own limits, so no tenant role has it and these routes answer 404 to non-operators.
 */
@RestController
@RequestMapping("/v1/admin/orgs/{orgId}/entitlements")
public class AdminEntitlementController {

    private final EntitlementService entitlements;

    public AdminEntitlementController(EntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    @GetMapping
    @PlatformAdminOnly
    public EffectiveEntitlementsRead effective(@PathVariable UUID orgId) {
        return EffectiveEntitlementsRead.from(entitlements.effectiveForOrg(orgId));
    }

    @PostMapping("/grants")
    @PlatformAdminOnly
    @ResponseStatus(HttpStatus.CREATED)
    public GrantCreated grant(@PathVariable UUID orgId, @Valid @RequestBody GrantRequest body, Principal principal) {
        Entitlement entitlement = entitlements.grant(orgId, body.featureKey(), body.source(), body.durationDays(), body.enabledOrDefault(),
            body.note(), body.limitValue(), principal.id());
        return new GrantCreated(entitlement.id(), entitlement.featureKey(), entitlement.source());
    }

    @DeleteMapping("/grants/{grantId}")
    @PlatformAdminOnly
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID orgId, @PathVariable UUID grantId) {
        Entitlement entitlement = entitlements.get(grantId);
        if (!entitlement.organizationId().equals(orgId)) {
            throw new EntitlementNotFoundError("Grant not found for this organization");
        }
        entitlements.revoke(grantId);
    }
}
