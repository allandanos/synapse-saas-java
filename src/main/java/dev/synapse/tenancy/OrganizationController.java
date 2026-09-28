package dev.synapse.tenancy;

import dev.synapse.core.context.TenantContext;
import dev.synapse.core.pagination.PageEnvelope;
import dev.synapse.core.pagination.Pagination;
import dev.synapse.core.security.Principal;
import dev.synapse.core.web.PlatformAdminOnly;
import dev.synapse.core.web.RequirePermission;
import dev.synapse.core.web.RequireTenant;
import dev.synapse.tenancy.dto.MemberInvite;
import dev.synapse.tenancy.dto.MembershipRead;
import dev.synapse.tenancy.dto.OrganizationCreate;
import dev.synapse.tenancy.dto.OrganizationRead;
import dev.synapse.tenancy.dto.OrganizationUpdate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/orgs}: organizations, the current tenant, its members, and the operator suspend switch. */
@RestController
@RequestMapping("/v1/orgs")
public class OrganizationController {

    private final OrganizationService organizations;

    public OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @GetMapping
    public PageEnvelope<OrganizationRead> listMyOrgs(Principal principal) {
        return organizations.listMyOrgs(principal.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationRead create(@Valid @RequestBody OrganizationCreate body, Principal principal) {
        return organizations.createOrganization(body.name(), body.slug(), principal.id());
    }

    @GetMapping("/current")
    @RequireTenant
    public OrganizationRead current(TenantContext tenant) {
        return organizations.getOrganization(tenant.organizationId());
    }

    @PatchMapping("/current")
    @RequirePermission("org:update")
    public OrganizationRead updateCurrent(@Valid @RequestBody OrganizationUpdate body, TenantContext tenant) {
        return organizations.updateOrganization(tenant.organizationId(), body.name(), body.settings());
    }

    @GetMapping("/current/members")
    @RequirePermission("member:read")
    public PageEnvelope<MembershipRead> members(
            TenantContext tenant,
            @RequestParam(defaultValue = "" + Pagination.DEFAULT_PAGE_LIMIT) @Min(1) @Max(Pagination.MAX_PAGE_LIMIT) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return organizations.listMembers(tenant.organizationId(), limit, offset);
    }

    @PostMapping("/current/members/invite")
    @RequirePermission("member:invite")
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipRead invite(@Valid @RequestBody MemberInvite body, TenantContext tenant) {
        return organizations.inviteMember(tenant.organizationId(), body.email(), body.roleKeysOrDefault());
    }

    // ── Platform operator (ADR 0008) ─────────────────────────────────────────────

    @PostMapping("/{orgId}/suspend")
    @PlatformAdminOnly
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(@PathVariable UUID orgId) {
        organizations.suspendOrganization(orgId);
    }

    @DeleteMapping("/{orgId}/suspend")
    @PlatformAdminOnly
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsuspend(@PathVariable UUID orgId) {
        organizations.unsuspendOrganization(orgId);
    }
}
