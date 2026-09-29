package dev.synapse.authorization.fga;

import dev.synapse.authorization.PermissionCatalog;
import dev.synapse.authorization.PermissionDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The OpenFGA authorization model, generated from the permission catalog (ADR 0009;
 * reference: {@code authorization/fga_model.py}).
 *
 * <p>RBAC stays the source of truth for <em>what</em> a role means
 * ({@link PermissionCatalog}); this class projects it into an OpenFGA model so
 * the two can never disagree:
 * <ul>
 *   <li>{@code type organization} carries one relation per system role
 *       ({@code owner}, {@code admin}, …) and one computed relation per
 *       permission ({@code can_org_delete}, …) that unions the roles holding
 *       it. Every {@code can_*} relation also accepts direct {@code [user]}
 *       tuples, so custom roles (any permission set) can be expressed.</li>
 *   <li>{@code type project} is the resource-level template domain apps copy:
 *       a {@code viewer} / {@code editor} relation that inherits from the org's
 *       permission or is granted per object (sharing).</li>
 * </ul>
 *
 * <p>{@link #build()} returns the JSON the OpenFGA API accepts;
 * {@link #renderDsl()} renders the same thing as the human-readable
 * {@code .fga} DSL for docs and review.
 */
public final class FgaModel {

    public static final String SCHEMA_VERSION = "1.1";
    public static final List<String> ROLE_ORDER = List.of("owner", "admin", "billing", "developer", "member");

    private FgaModel() {}

    /** {@code org:delete} → {@code can_org_delete}. */
    public static String relationFor(String permission) {
        return "can_" + permission.replace(':', '_');
    }

    public static List<String> rolesHolding(String permission) {
        return ROLE_ORDER.stream()
            .filter(role -> PermissionCatalog.SYSTEM_ROLES.get(role).permissions().contains(permission))
            .toList();
    }

    // ── JSON (API) form ──────────────────────────────────────────────────────────

    private static Map<String, Object> direct() {
        return Map.of("this", Map.of());
    }

    private static Map<String, Object> union(List<Map<String, Object>> children) {
        return children.size() == 1 ? children.get(0) : Map.of("union", Map.of("child", children));
    }

    private static Map<String, Object> computed(String relation) {
        return Map.of("computedUserset", Map.of("relation", relation));
    }

    private static Map<String, Object> tupleToUserset(String tupleset, String relation) {
        return Map.of("tupleToUserset",
            Map.of("tupleset", Map.of("relation", tupleset), "computedUserset", Map.of("relation", relation)));
    }

    private static Map<String, Object> directlyRelated(String type) {
        return Map.of("directly_related_user_types", List.of(Map.of("type", type)));
    }

    private static Map<String, Object> userType() {
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("type", "user");
        type.put("relations", Map.of());
        type.put("metadata", null);
        return type;
    }

    private static Map<String, Object> organizationType() {
        Map<String, Object> relations = new LinkedHashMap<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (String role : ROLE_ORDER) {
            relations.put(role, direct());
            metadata.put(role, directlyRelated("user"));
        }
        for (PermissionDef permission : PermissionCatalog.PERMISSIONS) {
            String relation = relationFor(permission.key());
            List<Map<String, Object>> children = new ArrayList<>();
            children.add(direct());
            rolesHolding(permission.key()).forEach(role -> children.add(computed(role)));
            relations.put(relation, union(children));
            metadata.put(relation, directlyRelated("user"));
        }
        return Map.of("type", "organization", "relations", relations, "metadata", Map.of("relations", metadata));
    }

    private static Map<String, Object> projectType() {
        Map<String, Object> relations = new LinkedHashMap<>();
        relations.put("org", direct());
        relations.put("viewer", union(List.of(direct(), computed("editor"), tupleToUserset("org", relationFor("project:read")))));
        relations.put("editor", union(List.of(direct(), tupleToUserset("org", relationFor("project:manage")))));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("org", directlyRelated("organization"));
        metadata.put("viewer", directlyRelated("user"));
        metadata.put("editor", directlyRelated("user"));
        return Map.of("type", "project", "relations", relations, "metadata", Map.of("relations", metadata));
    }

    /** The authorization model in the OpenFGA API's JSON form. */
    public static Map<String, Object> build() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("schema_version", SCHEMA_VERSION);
        model.put("type_definitions", List.of(userType(), organizationType(), projectType()));
        return model;
    }

    // ── DSL form (for humans) ────────────────────────────────────────────────────

    public static String renderDsl() {
        List<String> lines = new ArrayList<>(List.of(
            "model", "  schema " + SCHEMA_VERSION, "", "type user", "", "type organization", "  relations"));
        ROLE_ORDER.forEach(role -> lines.add("    define " + role + ": [user]"));
        for (PermissionDef permission : PermissionCatalog.PERMISSIONS) {
            String holders = String.join(" or ", rolesHolding(permission.key()));
            String rhs = "[user]" + (holders.isEmpty() ? "" : " or " + holders);
            lines.add("    define " + relationFor(permission.key()) + ": " + rhs);
        }
        lines.addAll(List.of(
            "",
            "type project",
            "  relations",
            "    define org: [organization]",
            "    define viewer: [user] or editor or " + relationFor("project:read") + " from org",
            "    define editor: [user] or " + relationFor("project:manage") + " from org",
            ""));
        return String.join("\n", lines);
    }
}
