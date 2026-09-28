package dev.synapse.authorization;

import dev.synapse.core.db.Rows;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RoleRepository {

    private static final RowMapper<Role> MAPPER = (rs, i) -> new Role(
        Rows.uuid(rs, "id"), Rows.uuid(rs, "organization_id"), rs.getString("key"), rs.getString("name"),
        rs.getString("description"), rs.getBoolean("is_system"), Rows.strings(rs, "permission_keys"));

    private static final String SELECT = """
        SELECT r.id, r.organization_id, r.key, r.name, r.description, r.is_system,
               COALESCE(array_agg(p.key ORDER BY p.key) FILTER (WHERE p.key IS NOT NULL), '{}') AS permission_keys
        FROM roles r
        LEFT JOIN role_permissions rp ON rp.role_id = r.id
        LEFT JOIN permissions p ON p.id = rp.permission_id
        """;
    private static final String GROUP = " GROUP BY r.id ";

    private final JdbcClient jdbc;

    public RoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** System roles + the org's custom roles, system first then by key. */
    public List<Role> listForOrganization(UUID organizationId) {
        return jdbc.sql(SELECT + " WHERE r.organization_id = :org OR r.organization_id IS NULL " + GROUP + " ORDER BY r.is_system DESC, r.key")
            .param("org", organizationId).query(MAPPER).list();
    }

    public List<Role> systemRoles() {
        return jdbc.sql(SELECT + " WHERE r.is_system " + GROUP).query(MAPPER).list();
    }

    public Optional<Role> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id " + GROUP).param("id", id).query(MAPPER).optional();
    }

    /** By key in the org's scope (its own custom role wins over a system role of the same key). */
    public Optional<Role> findByKeyInScope(String key, UUID organizationId) {
        return jdbc.sql(SELECT + " WHERE r.key = :key AND (r.organization_id = :org OR r.organization_id IS NULL) " + GROUP
                + " ORDER BY r.organization_id NULLS LAST LIMIT 1")
            .param("key", key).param("org", organizationId).query(MAPPER).optional();
    }

    public boolean customKeyExists(UUID organizationId, String key) {
        return jdbc.sql("SELECT count(*) FROM roles WHERE organization_id = :org AND key = :key")
            .param("org", organizationId).param("key", key).query(Long.class).single() > 0;
    }

    public Role insert(UUID organizationId, String key, String name, String description, boolean system) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO roles (id, organization_id, key, name, description, is_system) VALUES (:id, :org, :key, :name, :description, :system)")
            .param("id", id).param("org", organizationId).param("key", key).param("name", name)
            .param("description", description).param("system", system)
            .update();
        return findById(id).orElseThrow();
    }

    public void update(UUID id, String name, String description) {
        jdbc.sql("UPDATE roles SET name = :name, description = :description, updated_at = now() WHERE id = :id")
            .param("name", name).param("description", description).param("id", id).update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM roles WHERE id = :id").param("id", id).update();
    }

    /** Replace the role's permission set. Unknown keys are silently ignored (callers validate first). */
    public void setPermissions(UUID roleId, Collection<String> keys) {
        jdbc.sql("DELETE FROM role_permissions WHERE role_id = :role").param("role", roleId).update();
        addPermissions(roleId, keys);
    }

    public void addPermissions(UUID roleId, Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        jdbc.sql("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT :role, p.id FROM permissions p WHERE p.key = ANY(:keys)
                ON CONFLICT DO NOTHING
                """)
            .param("role", roleId).param("keys", keys.toArray(String[]::new)).update();
    }

    public List<UUID> membershipIdsForRole(UUID roleId) {
        return jdbc.sql("SELECT membership_id FROM membership_roles WHERE role_id = :role").param("role", roleId).query(UUID.class).list();
    }

    public void clearMembershipRoles(UUID roleId) {
        jdbc.sql("DELETE FROM membership_roles WHERE role_id = :role").param("role", roleId).update();
    }
}
