package dev.synapse.authorization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Canonical permission catalog and system roles — a line-by-line port of the
 * reference's {@code authorization/permissions.py} (ADR 0012 §6). Seeded into
 * {@code permissions} / {@code roles} idempotently at startup.
 */
public final class PermissionCatalog {

    private PermissionCatalog() {}

    public static final List<PermissionDef> PERMISSIONS = List.of(
        // Organization
        new PermissionDef("org:read", "org", "read", "View organization details"),
        new PermissionDef("org:update", "org", "update", "Update organization profile and settings"),
        new PermissionDef("org:delete", "org", "delete", "Delete the organization"),
        // Members
        new PermissionDef("member:read", "member", "read", "List members and their roles"),
        new PermissionDef("member:invite", "member", "invite", "Invite new members"),
        new PermissionDef("member:update", "member", "update", "Change member roles and status"),
        new PermissionDef("member:remove", "member", "remove", "Remove members from the organization"),
        // Roles
        new PermissionDef("role:manage", "role", "manage", "Create, update, and delete custom roles"),
        // Billing & subscription
        new PermissionDef("billing:read", "billing", "read", "View subscription, plans, and invoices"),
        new PermissionDef("billing:manage", "billing", "manage", "Change plans, start trials, manage payment"),
        // Usage & audit
        new PermissionDef("usage:read", "usage", "read", "View usage meters and limits"),
        new PermissionDef("audit:read", "audit", "read", "View the organization audit log"),
        // Webhooks
        new PermissionDef("webhook:manage", "webhook", "manage", "Manage webhook endpoints and view deliveries"),
        // Entitlements
        new PermissionDef("entitlement:manage", "entitlement", "manage", "Grant or revoke feature entitlements (operator)"),
        new PermissionDef("apikey:manage", "apikey", "manage", "Create, list, and revoke API keys"),
        // Files
        new PermissionDef("file:read", "file", "read", "List and download organization files"),
        new PermissionDef("file:write", "file", "write", "Upload and delete organization files"),
        // Project-scoped example (the pattern domain apps extend)
        new PermissionDef("project:read", "project", "read", "View projects"),
        new PermissionDef("project:manage", "project", "manage", "Create, update, and delete projects"),
        // Agents (registry governance — ADR 0007)
        new PermissionDef("agents:read", "agents", "read", "View registered agents"),
        new PermissionDef("agents:manage", "agents", "manage", "Register, update, enable/disable agents"));

    public static final Set<String> PERMISSION_KEYS = PERMISSIONS.stream().map(PermissionDef::key).collect(Collectors.toUnmodifiableSet());

    public static final String SYSTEM_ROLE_OWNER = "owner";
    public static final String SYSTEM_ROLE_ADMIN = "admin";
    public static final String SYSTEM_ROLE_BILLING = "billing";
    public static final String SYSTEM_ROLE_DEVELOPER = "developer";
    public static final String SYSTEM_ROLE_MEMBER = "member";

    // entitlement:manage is an OPERATOR permission: a tenant must never be able to
    // grant itself features or raise its own limits. No tenant system role carries it.
    private static final Set<String> OWNER = PERMISSION_KEYS.stream().filter(k -> !k.equals("entitlement:manage")).collect(Collectors.toSet());
    private static final Set<String> ADMIN = OWNER.stream().filter(k -> !k.equals("org:delete")).collect(Collectors.toSet());
    private static final Set<String> BILLING = Set.of("org:read", "billing:read", "billing:manage", "usage:read");
    private static final Set<String> DEVELOPER = Set.of("org:read", "member:read", "project:read", "project:manage",
        "webhook:manage", "usage:read", "apikey:manage", "agents:read");
    private static final Set<String> MEMBER = Set.of("org:read", "project:read");

    /** Insertion-ordered like the reference dict: owner, admin, billing, developer, member. */
    public static final Map<String, SystemRole> SYSTEM_ROLES = systemRoles();

    private static Map<String, SystemRole> systemRoles() {
        Map<String, SystemRole> roles = new LinkedHashMap<>();
        roles.put(SYSTEM_ROLE_OWNER, new SystemRole(SYSTEM_ROLE_OWNER, "Owner", "Full control, including deleting the organization", sorted(OWNER)));
        roles.put(SYSTEM_ROLE_ADMIN, new SystemRole(SYSTEM_ROLE_ADMIN, "Admin", "Manage everything except deleting the organization", sorted(ADMIN)));
        roles.put(SYSTEM_ROLE_BILLING, new SystemRole(SYSTEM_ROLE_BILLING, "Billing", "Manage subscription, plans, and invoices", sorted(BILLING)));
        roles.put(SYSTEM_ROLE_DEVELOPER, new SystemRole(SYSTEM_ROLE_DEVELOPER, "Developer", "Build on the platform: projects, webhooks, usage visibility", sorted(DEVELOPER)));
        roles.put(SYSTEM_ROLE_MEMBER, new SystemRole(SYSTEM_ROLE_MEMBER, "Member", "Day-to-day access to org resources", sorted(MEMBER)));
        return Map.copyOf(roles) instanceof Map<String, SystemRole> m ? orderedCopy(roles) : roles;
    }

    private static Map<String, SystemRole> orderedCopy(Map<String, SystemRole> roles) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(roles));
    }

    private static List<String> sorted(Set<String> keys) {
        return List.copyOf(new TreeSet<>(keys));
    }
}
