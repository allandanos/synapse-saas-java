package dev.synapse.authorization;

import dev.synapse.authorization.fga.FgaClient;
import dev.synapse.authorization.fga.FgaError;
import dev.synapse.authorization.fga.FgaModel;
import dev.synapse.authorization.fga.FgaTuple;
import dev.synapse.authorization.fga.TupleSync;
import dev.synapse.core.cache.Caches;
import dev.synapse.core.cache.DeferredBumps;
import dev.synapse.core.cache.VersionedCache;
import dev.synapse.core.errors.ConflictError;
import dev.synapse.core.errors.PermissionDeniedError;
import dev.synapse.core.errors.RoleNotFoundError;
import dev.synapse.core.errors.SystemRoleImmutableError;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.metrics.FrameworkMetrics;
import dev.synapse.tenancy.MembershipRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RBAC over Postgres (reference: {@code authorization/service.py}). The
 * denormalised {@code memberships.permission_keys} is maintained on every
 * role/membership write so checks are one indexed read, and the result is
 * memoised for 30 s in the {@code perm} versioned cache.
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private final RoleRepository roles;
    private final MembershipRepository memberships;
    private final VersionedCache permissionCache;
    private final VersionedCache fgaCache;
    private final SynapseProperties props;
    private final FgaClient fga;
    private final TupleSync tupleSync;
    private final FrameworkMetrics metrics;

    public AuthorizationService(RoleRepository roles, MembershipRepository memberships, Caches caches,
                                SynapseProperties props, FgaClient fga, TupleSync tupleSync, FrameworkMetrics metrics) {
        this.roles = roles;
        this.memberships = memberships;
        this.permissionCache = caches.permissions();
        this.fgaCache = caches.fga();
        this.props = props;
        this.fga = fga;
        this.tupleSync = tupleSync;
        this.metrics = metrics;
    }

    // ── Checks ───────────────────────────────────────────────────────────────────

    /** Effective permission set for (user, org): the active membership's denormalised keys, cached briefly. */
    @Transactional(readOnly = true)
    public Set<String> permissionKeysFor(UUID userId, UUID organizationId) {
        String cacheKey = userId + ":" + organizationId;
        VersionedCache.Versioned cached = permissionCache.getVersioned(cacheKey);
        if (cached.body() != null) {
            return cached.body().isEmpty() ? Set.of() : Set.of(cached.body().split(","));
        }
        Set<String> keys = memberships.findActive(organizationId, userId)
            .map(m -> Set.copyOf(m.permissionKeys()))
            .orElse(Set.of());
        // Under the version seen at READ time: a bump in between must leave the new version empty.
        permissionCache.set(cacheKey, String.join(",", new TreeSet<>(keys)), cached.version());
        return keys;
    }

    /**
     * The org-level permission check every route asks.
     *
     * <p>rbac: the member's denormalised permission set. openfga: ask the store
     * ({@code user:<id>} {@code can_<perm>} {@code organization:<id>}), cached
     * briefly and invalidated with the permission cache; on an outage the
     * configured fail mode decides (closed ⇒ deny, rbac ⇒ fall back).
     */
    @Transactional(readOnly = true)
    public boolean userCan(UUID userId, UUID organizationId, String permission) {
        if (props.openfgaBackend()) {
            return fgaAllowed(userId, permission, FgaTuple.organizationObject(organizationId));
        }
        return permissionKeysFor(userId, organizationId).contains(permission);
    }

    /**
     * Resource-level check ({@code project:manage} on project X). The RBAC
     * backend answers at org level only: pass organization ids as
     * {@code objectType="organization"}.
     */
    @Transactional(readOnly = true)
    public boolean userCanOn(UUID userId, String permission, String objectType, Object objectId) {
        if (props.openfgaBackend()) {
            return fgaAllowed(userId, permission, objectType + ":" + objectId);
        }
        if (!"organization".equals(objectType)) {
            throw new UnsupportedOperationException("resource-level checks need the openfga backend; check the org instead");
        }
        return userCan(userId, UUID.fromString(String.valueOf(objectId)), permission);
    }

    private boolean fgaAllowed(UUID userId, String permission, String object) {
        String scope = userId + ":" + object;
        VersionedCache.Scoped cached = fgaCache.getScoped(scope + ":" + permission, scope);
        if (cached.body() != null) {
            return "1".equals(cached.body());
        }
        boolean allowed;
        try {
            allowed = fga.check(FgaTuple.userObject(userId), FgaModel.relationFor(permission), object);
        } catch (FgaError error) {
            metrics.fgaCheck("error");
            if ("rbac".equals(props.openfgaFailMode()) && object.startsWith("organization:")) {
                log.warn("fga_check_failed_falling_back_to_rbac permission={} error={}", permission, error.toString());
                return permissionKeysFor(userId, UUID.fromString(object.substring("organization:".length()))).contains(permission);
            }
            log.error("fga_check_failed_closed permission={} object={} error={}", permission, object, error.toString());
            return false; // an outage is never cached: the next call asks again
        }
        metrics.fgaCheck(allowed ? "allowed" : "denied");
        fgaCache.setScoped(scope + ":" + permission, allowed ? "1" : "0", cached.token());
        return allowed;
    }

    // ── Invalidation ─────────────────────────────────────────────────────────────

    /**
     * Drop the cached permission set for one (user, org) pair: role and
     * membership changes must be visible to the very next request. Bumped now
     * (this request recomputes) and again after commit (nobody caches the
     * pre-commit rows under the new version for a whole TTL).
     */
    public void invalidateUserPermissions(UUID userId, UUID organizationId) {
        if (userId == null) {
            return;
        }
        String key = userId + ":" + organizationId;
        permissionCache.bump(key);
        DeferredBumps.defer(permissionCache, key);
        // OpenFGA: drop cached decisions for this member and resync their tuples
        String fgaKey = userId + ":" + FgaTuple.organizationObject(organizationId);
        fgaCache.bump(fgaKey);
        DeferredBumps.defer(fgaCache, fgaKey);
        tupleSync.queue(organizationId, userId);
    }

    /** Queue an OpenFGA tuple resync for one member (no-op on the rbac backend). */
    public void queueTupleSync(UUID organizationId, UUID userId) {
        tupleSync.queue(organizationId, userId);
    }

    /** Invalidate every member of an org (role edits, custom-role changes). */
    @Transactional
    public void invalidateOrganizationPermissions(UUID organizationId) {
        memberships.userIdsIn(organizationId).forEach(userId -> invalidateUserPermissions(userId, organizationId));
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
        invalidateOrganizationPermissions(organizationId);
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
        invalidateOrganizationPermissions(organizationId);
        return roles.findById(role.id()).orElseThrow();
    }

    @Transactional
    public void deleteCustomRole(UUID roleId, UUID organizationId) {
        Role role = scopedCustomRole(roleId, organizationId);
        List<UUID> holders = roles.membershipIdsForRole(role.id());
        roles.clearMembershipRoles(role.id());
        roles.delete(role.id());
        // Holders keep a denormalised permission_keys column: recompute it or they keep the dead role's permissions.
        holders.forEach(this::recomputeMembershipPermissions);
        invalidateOrganizationPermissions(organizationId);
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
        invalidateMembership(membershipId);
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
        invalidateMembership(membershipId); // an empty role list still has to drop the cached set
    }

    @Transactional
    public void recomputeMembershipPermissions(UUID membershipId) {
        memberships.updatePermissionKeys(membershipId, memberships.permissionKeysFromRoles(membershipId));
        invalidateMembership(membershipId);
    }

    /** Invalidate the caches (and, on the OpenFGA backend, the tuples) of one membership. */
    public void invalidateMembership(UUID membershipId) {
        memberships.findById(membershipId).ifPresent(m -> invalidateUserPermissions(m.userId(), m.organizationId()));
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
