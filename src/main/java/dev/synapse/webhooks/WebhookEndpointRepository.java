package dev.synapse.webhooks;

import dev.synapse.core.db.Rows;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code webhook_endpoints}. The management routes are milestone 5; the worker only reads. */
@Repository
public class WebhookEndpointRepository {

    private static final String COLUMNS = "id, organization_id, url, secret_encrypted, description, events, is_active, created_at";

    private final JdbcClient jdbc;
    private final RowMapper<WebhookEndpoint> mapper = (rs, i) -> new WebhookEndpoint(
        Rows.uuid(rs, "id"), Rows.uuid(rs, "organization_id"), rs.getString("url"), rs.getBytes("secret_encrypted"),
        rs.getString("description"), Rows.strings(rs, "events"), rs.getBoolean("is_active"), Rows.instant(rs, "created_at"));

    public WebhookEndpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<WebhookEndpoint> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_endpoints WHERE id = :id").param("id", id).query(mapper).optional();
    }

    /** Endpoints of one org, oldest first. The secret column is read but never serialised. */
    public List<WebhookEndpoint> listForOrganization(UUID organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_endpoints WHERE organization_id = :org ORDER BY created_at, id")
            .param("org", organizationId).query(mapper).list();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM webhook_endpoints WHERE id = :id").param("id", id).update();
    }

    /** Active endpoints of the org subscribed to the type — an empty filter means "all". */
    public List<UUID> subscribedTo(UUID organizationId, String eventType) {
        return jdbc.sql("""
                SELECT id FROM webhook_endpoints
                WHERE organization_id = :org AND is_active = true AND (events = '{}' OR :eventType = ANY(events))
                """)
            .param("org", organizationId).param("eventType", eventType).query(UUID.class).list();
    }

    /** Returns the endpoint id; the plaintext secret is the caller's to show exactly once. */
    public UUID insert(UUID organizationId, String url, byte[] secretEncrypted, String description, List<String> events) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO webhook_endpoints (id, organization_id, url, secret_encrypted, description, events, is_active)
                VALUES (:id, :org, :url, :secret, :description, :events, true)
                """)
            .param("id", id).param("org", organizationId).param("url", url).param("secret", secretEncrypted)
            .param("description", description).param("events", events.toArray(String[]::new))
            .update();
        return id;
    }
}
