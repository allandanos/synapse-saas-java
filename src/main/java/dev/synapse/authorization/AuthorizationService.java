package dev.synapse.authorization;

import dev.synapse.core.errors.ConflictError;
import dev.synapse.core.errors.PermissionDeniedError;
import dev.synapse.core.errors.RoleNotFoundError;
import dev.synapse.core.errors.SystemRoleImmutableError;
import dev.synapse.tenancy.MembershipRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RBAC over Postgres (reference: {@code authorization/service.py}). The
 * denormalised {@code memberships.permission_keys} is maintained on every
 * role/membership write so checks are one indexed read. (OpenFGA is milestone 7.)
 */
@Service
public class AuthorizationService {

    private final RoleRepository roles;
    private final MembershipRepository memberships;

    public AuthorizationService(RoleRepository roles, MembershipRepository memberships) {
        this.roles = roles;
        this.memberships = memberships;
    }

    // ── Checks ───────────────────────────────────────────────────────────────────

    /** Effective permission set for (user, org): the active membership's denormalised keys. */
    @Transactional(readOnly = true)
    public Set<String> permissionKeysFor(UUID userId, UUID organizationId) {
        return memberships.findActive(organizationId, userId)
            .map(m -> Set.copyOf(m.permissionKeys()))
            .orElse(Set.of());
    }

    // ── Role management ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Role> listRoles(UUID organizationId) {
        return roles.listForOrganization(organizationId);
    }

    @Transactional
    public Role createCustomRole(UUID organizationId, String key, String name, String description, List<String> permissionKeys) {
        rejectUnknown(permissionKeys);
        if (roles.customKeyExists(organizationId, key)) { // the unique constraint would 500; say why instead
            throw new ConflictError("Role key '" + key + "' already exists in this organization", Map.of("key", key));
        }
        Role role = roles.insert(organizationId, key, name, description, false);
        roles.setPermissions(role.id(), permissionKeys);
        return roles.findById(role.id()).orElseThrow();
    }

    @Transactional
    public Role updateCustomRole(UUID roleId, UUID organizationId, String name, String description, List<String> permissionKeys) {
        Role role = scopedCustomRole(roleId, organizationId);
        roles.update(role.id(), name != null ? name : role.name(), description != null ? description : role.description());
        if (permissionKeys != null) {
            rejectUnknown(permissionKeys);
            roles.setPermissions(role.id(), permissionKeys);
            refreshMembershipsForRole(role.id());
        }
        return roles.findById(role.id()).orElseThrow();
    }

    @Transactional
    public void deleteCustomRole(UUID roleId, UUID organizationId) {
        Role role = scopedCustomRole(roleId, organizationId);
        List<UUID> holders = roles.membershipIdsForRole(role.id());
        roles.clearMembershipRoles(role.id());
        roles.delete(role.id());
        holders.forEach(this::recomputeMembershipPermissions);
    }

    /**
     * Attach a role (org custom or system) to a membership and grow its
     * permission set. Returns the new permission set so callers chaining
     * several roles do not need to re-read the row.
     */
    @Transactional
    public List<String> attachRole(UUID membershipId, UUID organizationId, List<String> currentKeys, String roleKey) {
        Role role = roles.findByKeyInScope(roleKey, organizationId)
            .orElseThrow(() -> new RoleNotFoundError("Role '" + roleKey + "' not found"));
        memberships.addRole(membershipId, role.id());
        Set<String> union = new TreeSet<>(currentKeys);
        union.addAll(role.permissions());
        List<String> keys = List.copyOf(union);
        memberships.updatePermissionKeys(membershipId, keys);
        return keys;
    }

    @Transactional
    public void replaceRoles(UUID membershipId, UUID organizationId, List<String> roleKeys) {
        memberships.clearRoles(membershipId);
        List<String> keys = List.of();
        memberships.updatePermissionKeys(membershipId, keys);
        for (String roleKey : roleKeys) {
            keys = attachRole(membershipId, organizationId, keys, roleKey);
        }
    }

    @Transactional
    public void recomputeMembershipPermissions(UUID membershipId) {
        memberships.updatePermissionKeys(membershipId, memberships.permissionKeysFromRoles(membershipId));
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private Role scopedCustomRole(UUID roleId, UUID organizationId) {
        Role role = roles.findById(roleId).orElse(null);
        if (role == null || !organizationId.equals(role.organizationId())) {
            throw new RoleNotFoundError("Role not found"); // system roles are not org-scoped ⇒ 404 too
        }
        if (role.system()) {
            throw new SystemRoleImmutableError("System roles cannot be modified");
        }
        return role;
    }

    private void refreshMembershipsForRole(UUID roleId) {
        roles.membershipIdsForRole(roleId).forEach(this::recomputeMembershipPermissions);
    }

    private static void rejectUnknown(List<String> permissionKeys) {
        List<String> unknown = new ArrayList<>(new TreeSet<>(permissionKeys));
        unknown.removeAll(PermissionCatalog.PERMISSION_KEYS);
        if (!unknown.isEmpty()) {
            throw new PermissionDeniedError("Unknown permissions: " + pyList(unknown), Map.of("unknown", unknown));
        }
    }

    /** Python's {@code repr} of a list of strings, as the reference embeds it in {@code detail}. */
    public static String pyList(List<String> values) {
        return values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", ", "[", "]"));
    }
}
