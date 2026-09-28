package dev.synapse.authorization;

import dev.synapse.authorization.dto.PermissionRead;
import dev.synapse.authorization.dto.RoleCreate;
import dev.synapse.authorization.dto.RoleRead;
import dev.synapse.authorization.dto.RoleUpdate;
import dev.synapse.core.context.TenantContext;
import dev.synapse.core.web.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/roles} (org-scoped RBAC) and the public {@code /v1/permissions} catalog. */
@RestController
public class RoleController {

    private final AuthorizationService authz;

    public RoleController(AuthorizationService authz) {
        this.authz = authz;
    }

    @GetMapping("/v1/permissions")
    public List<PermissionRead> permissions() {
        return PermissionCatalog.PERMISSIONS.stream()
            .map(p -> new PermissionRead(p.key(), p.resource(), p.action(), p.description()))
            .toList();
    }

    @GetMapping("/v1/roles")
    @RequirePermission("member:read")
    public List<RoleRead> list(TenantContext tenant) {
        return authz.listRoles(tenant.organizationId()).stream().map(RoleRead::from).toList();
    }

    @PostMapping("/v1/roles")
    @RequirePermission("role:manage")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleRead create(@Valid @RequestBody RoleCreate body, TenantContext tenant) {
        return RoleRead.from(authz.createCustomRole(tenant.organizationId(), body.key(), body.name(), body.description(), body.permissions()));
    }

    @PatchMapping("/v1/roles/{roleId}")
    @RequirePermission("role:manage")
    public RoleRead update(@PathVariable UUID roleId, @Valid @RequestBody RoleUpdate body, TenantContext tenant) {
        return RoleRead.from(authz.updateCustomRole(roleId, tenant.organizationId(), body.name(), body.description(), body.permissions()));
    }

    @DeleteMapping("/v1/roles/{roleId}")
    @RequirePermission("role:manage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID roleId, TenantContext tenant) {
        authz.deleteCustomRole(roleId, tenant.organizationId());
    }
}
