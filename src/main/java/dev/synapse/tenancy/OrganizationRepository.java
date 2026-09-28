package dev.synapse.tenancy;

import dev.synapse.core.db.Json;
import dev.synapse.core.db.Rows;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationRepository {

    private static final String COLUMNS = "id, slug, name, status, owner_user_id, settings::text AS settings, created_at, deleted_at";

    private final JdbcClient jdbc;
    private final RowMapper<Organization> mapper;

    public OrganizationRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.mapper = (rs, i) -> new Organization(
            Rows.uuid(rs, "id"), rs.getString("slug"), rs.getString("name"), rs.getString("status"),
            Rows.uuid(rs, "owner_user_id"), json.readMap(rs.getString("settings")), Rows.local(rs, "created_at"),
            Rows.local(rs, "deleted_at"));
    }

    public Optional<Organization> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM organizations WHERE id = :id").param("id", id).query(mapper).optional();
    }

    public Optional<Organization> findBySlug(String slug) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM organizations WHERE slug = :slug AND deleted_at IS NULL")
            .param("slug", slug).query(mapper).optional();
    }

    public boolean slugExists(String slug) {
        return findBySlug(slug).isPresent();
    }

    public Organization insert(String slug, String name, UUID ownerUserId, Json json) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO organizations (id, slug, name, status, owner_user_id, settings)
                VALUES (:id, :slug, :name, 'active', :owner, CAST(:settings AS jsonb))
                """)
            .param("id", id).param("slug", slug).param("name", name).param("owner", ownerUserId)
            .param("settings", json.write(Map.of()))
            .update();
        return findById(id).orElseThrow();
    }

    public void update(UUID id, String name, String settingsJson) {
        jdbc.sql("UPDATE organizations SET name = :name, settings = CAST(:settings AS jsonb), updated_at = now() WHERE id = :id")
            .param("name", name).param("settings", settingsJson).param("id", id).update();
    }

    public void updateStatus(UUID id, String status) {
        jdbc.sql("UPDATE organizations SET status = :status, updated_at = now() WHERE id = :id")
            .param("status", status).param("id", id).update();
    }
}
