package dev.synapse.tenancy;

import dev.synapse.core.context.TenantContext;
import dev.synapse.core.web.RequirePermission;
import dev.synapse.tenancy.dto.MemberUpdate;
import dev.synapse.tenancy.dto.MembershipRead;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/memberships/{id}}: roles/status changes and removal; cross-tenant ids are 404. */
@RestController
@RequestMapping("/v1/memberships")
public class MembershipController {

    private final OrganizationService organizations;

    public MembershipController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @PatchMapping("/{membershipId}")
    @RequirePermission("member:update")
    public MembershipRead update(@PathVariable UUID membershipId, @Valid @RequestBody MemberUpdate body, TenantContext tenant) {
        return organizations.updateMembership(membershipId, tenant, body.roleKeys(), body.status());
    }

    @DeleteMapping("/{membershipId}")
    @RequirePermission("member:remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID membershipId, TenantContext tenant) {
        organizations.removeMember(membershipId, tenant);
    }
}
