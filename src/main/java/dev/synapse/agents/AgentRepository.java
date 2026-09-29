package dev.synapse.agents;

import dev.synapse.core.db.Json;
import dev.synapse.core.db.Rows;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code agents}: org-scoped, soft-deleted, slug unique per org including deleted rows. */
@Repository
public class AgentRepository {

    private static final String COLUMNS = """
        id, organization_id, slug, name, description, status, config::text AS config, deleted_at, created_at, updated_at
        """;

    private final JdbcClient jdbc;
    private final Json json;
    private final RowMapper<Agent> mapper;

    public AgentRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new Agent(Rows.uuid(rs, "id"), Rows.uuid(rs, "organization_id"), rs.getString("slug"),
            rs.getString("name"), rs.getString("description"), rs.getString("status"), json.readMap(rs.getString("config")),
            Rows.instant(rs, "deleted_at"), Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"));
    }

    /** Live agents of the org, oldest first (the reference orders by {@code created_at}). */
    public List<Agent> listForOrganization(UUID organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agents WHERE organization_id = :org AND deleted_at IS NULL ORDER BY created_at, id")
            .param("org", organizationId).query(mapper).list();
    }

    public Optional<Agent> findLive(UUID id, UUID organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agents WHERE id = :id AND organization_id = :org AND deleted_at IS NULL")
            .param("id", id).param("org", organizationId).query(mapper).optional();
    }

    /** Slug lookup that deliberately sees soft-deleted rows: the unique index covers them. */
    public Optional<Agent> findBySlug(UUID organizationId, String slug) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agents WHERE organization_id = :org AND slug = :slug")
            .param("org", organizationId).param("slug", slug).query(mapper).optional();
    }

    public Agent insert(UUID organizationId, String slug, String name, String description, Map<String, Object> config) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO agents (id, organization_id, slug, name, description, status, config)
                VALUES (:id, :org, :slug, :name, :description, 'active', CAST(:config AS jsonb))
                """)
            .param("id", id).param("org", organizationId).param("slug", slug).param("name", name)
            .param("description", description).param("config", json.write(config))
            .update();
        return findLive(id, organizationId).orElseThrow();
    }

    public Agent update(UUID id, UUID organizationId, String name, String description, Map<String, Object> config) {
        jdbc.sql("""
                UPDATE agents SET name = :name, description = :description, config = CAST(:config AS jsonb), updated_at = now()
                WHERE id = :id AND organization_id = :org
                """)
            .param("id", id).param("org", organizationId).param("name", name).param("description", description)
            .param("config", json.write(config))
            .update();
        return findLive(id, organizationId).orElseThrow();
    }

    public Agent setStatus(UUID id, UUID organizationId, String status) {
        jdbc.sql("UPDATE agents SET status = :status, updated_at = now() WHERE id = :id AND organization_id = :org")
            .param("id", id).param("org", organizationId).param("status", status).update();
        return findLive(id, organizationId).orElseThrow();
    }

    /** Soft delete: registry rows are billing history and are never hard-removed. */
    public void softDelete(UUID id, UUID organizationId, Instant at) {
        jdbc.sql("""
                UPDATE agents SET deleted_at = :at, status = 'disabled', updated_at = now()
                WHERE id = :id AND organization_id = :org
                """)
            .param("id", id).param("org", organizationId).param("at", Rows.at(at)).update();
    }
}
