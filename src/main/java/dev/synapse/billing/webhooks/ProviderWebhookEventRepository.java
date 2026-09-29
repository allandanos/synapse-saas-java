package dev.synapse.billing.webhooks;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The provider webhook idempotency ledger ({@code provider_webhook_events}).
 * Unique per {@code (provider, provider_event_id)}: a no-op insert means the
 * event was already processed, so a replay answers 200 without re-applying.
 */
@Repository
public class ProviderWebhookEventRepository {

    private final JdbcClient jdbc;

    public ProviderWebhookEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty when the event was seen before. */
    public Optional<UUID> claim(String provider, String providerEventId, String eventType) {
        return jdbc.sql("""
                INSERT INTO provider_webhook_events (id, provider, provider_event_id, event_type)
                VALUES (:id, :provider, :eventId, :eventType)
                ON CONFLICT (provider, provider_event_id) DO NOTHING
                RETURNING id
                """)
            .param("id", UUID.randomUUID()).param("provider", provider).param("eventId", providerEventId).param("eventType", eventType)
            .query(UUID.class).optional();
    }

    /** {@code error} is null when everything applied; the row is the audit trail either way. */
    public void markProcessed(UUID id, Instant processedAt, String error) {
        jdbc.sql("UPDATE provider_webhook_events SET processed_at = :processedAt, error = :error WHERE id = :id")
            .param("id", id).param("processedAt", dev.synapse.core.db.Rows.at(processedAt)).param("error", error)
            .update();
    }
}
