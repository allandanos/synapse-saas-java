package dev.synapse.webhooks;

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

/** {@code webhook_deliveries}: claimed with {@code FOR UPDATE SKIP LOCKED} so N workers never POST twice. */
@Repository
public class WebhookDeliveryRepository {

    private static final String COLUMNS = """
        id, endpoint_id, organization_id, outbox_event_id, event_type, payload::text AS payload, status, attempts, max_attempts,
        next_attempt_at, last_response_code, last_error, response_excerpt, delivered_at, created_at
        """;

    private final JdbcClient jdbc;
    private final Json json;
    private final RowMapper<WebhookDelivery> mapper;

    public WebhookDeliveryRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new WebhookDelivery(Rows.uuid(rs, "id"), Rows.uuid(rs, "endpoint_id"), Rows.uuid(rs, "organization_id"),
            Rows.uuid(rs, "outbox_event_id"), rs.getString("event_type"), json.readMap(rs.getString("payload")), rs.getString("status"),
            rs.getInt("attempts"), rs.getInt("max_attempts"), Rows.instant(rs, "next_attempt_at"), Rows.intOrNull(rs, "last_response_code"),
            rs.getString("last_error"), rs.getString("response_excerpt"), Rows.instant(rs, "delivered_at"), Rows.instant(rs, "created_at"));
    }

    public Optional<WebhookDelivery> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_deliveries WHERE id = :id").param("id", id).query(mapper).optional();
    }

    public List<WebhookDelivery> forOrg(UUID organizationId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_deliveries WHERE organization_id = :org ORDER BY created_at DESC LIMIT :limit")
            .param("org", organizationId).param("limit", limit).query(mapper).list();
    }

    public Optional<WebhookDelivery> findForOrg(UUID id, UUID organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_deliveries WHERE id = :id AND organization_id = :org")
            .param("id", id).param("org", organizationId).query(mapper).optional();
    }

    /** One page of an org's deliveries, newest first, optionally narrowed to one endpoint. */
    public List<WebhookDelivery> page(UUID organizationId, UUID endpointId, int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM webhook_deliveries
                 WHERE organization_id = :org AND (CAST(:endpoint AS uuid) IS NULL OR endpoint_id = CAST(:endpoint AS uuid))
                 ORDER BY created_at DESC
                 LIMIT :limit OFFSET :offset
                """)
            .param("org", organizationId).param("endpoint", endpointId == null ? null : endpointId.toString())
            .param("limit", limit).param("offset", offset).query(mapper).list();
    }

    public long count(UUID organizationId, UUID endpointId) {
        return jdbc.sql("""
                SELECT count(*) FROM webhook_deliveries
                WHERE organization_id = :org AND (CAST(:endpoint AS uuid) IS NULL OR endpoint_id = CAST(:endpoint AS uuid))
                """)
            .param("org", organizationId).param("endpoint", endpointId == null ? null : endpointId.toString())
            .query(Long.class).single();
    }

    /** Requeue: back to pending with a clean attempt ladder, due now. */
    public void resetForRetry(UUID id, Instant at) {
        jdbc.sql("UPDATE webhook_deliveries SET status = 'pending', attempts = 0, next_attempt_at = :at WHERE id = :id")
            .param("id", id).param("at", Rows.at(at)).update();
    }

    /** Due pending deliveries, claimed for this transaction only. */
    public List<UUID> claimDue(int limit) {
        return jdbc.sql("""
                SELECT id FROM webhook_deliveries
                WHERE status = 'pending' AND next_attempt_at <= now()
                ORDER BY created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
            .param("limit", limit).query(UUID.class).list();
    }

    public UUID insert(UUID endpointId, UUID organizationId, UUID outboxEventId, String eventType, Map<String, Object> payload,
                       int maxAttempts) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO webhook_deliveries (id, endpoint_id, organization_id, outbox_event_id, event_type, payload, status,
                                                attempts, max_attempts)
                VALUES (:id, :endpoint, :org, :outbox, :eventType, CAST(:payload AS jsonb), 'pending', 0, :maxAttempts)
                """)
            .param("id", id).param("endpoint", endpointId).param("org", organizationId).param("outbox", outboxEventId)
            .param("eventType", eventType).param("payload", json.write(payload)).param("maxAttempts", maxAttempts)
            .update();
        return id;
    }

    public void markDelivered(UUID id, Instant deliveredAt, int responseCode, String excerpt) {
        jdbc.sql("""
                UPDATE webhook_deliveries SET status = 'delivered', delivered_at = :deliveredAt, last_response_code = :code,
                       response_excerpt = :excerpt WHERE id = :id
                """)
            .param("id", id).param("deliveredAt", Rows.at(deliveredAt)).param("code", responseCode).param("excerpt", excerpt)
            .update();
    }

    public void markFailure(UUID id, String status, int attempts, Integer responseCode, String error, Instant nextAttemptAt) {
        jdbc.sql("""
                UPDATE webhook_deliveries SET status = :status, attempts = :attempts, last_response_code = :code, last_error = :error,
                       next_attempt_at = COALESCE(:nextAttempt, next_attempt_at) WHERE id = :id
                """)
            .param("id", id).param("status", status).param("attempts", attempts).param("code", responseCode).param("error", error)
            .param("nextAttempt", Rows.at(nextAttemptAt))
            .update();
    }
}
