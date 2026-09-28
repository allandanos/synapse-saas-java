package dev.synapse.core.outbox;

import dev.synapse.core.db.Json;
import dev.synapse.core.ids.Ids;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transactional outbox writer: the event row commits atomically with the state
 * change or not at all (a worker later drains {@code outbox_events}). Calling
 * it outside a transaction is a programming error, so it refuses.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final Json json;

    public OutboxWriter(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void append(String eventType, String aggregateType, UUID aggregateId, UUID organizationId, Map<String, ?> payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("append_outbox must run inside the mutating transaction");
        }
        jdbc.sql("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, organization_id, event_type, payload, attempts, audience)
                VALUES (:id, :aggregateType, :aggregateId, :organizationId, :eventType, CAST(:payload AS jsonb), 0, :audience)
                """)
            .param("id", Ids.uuidV7())
            .param("aggregateType", aggregateType)
            .param("aggregateId", aggregateId)
            .param("organizationId", organizationId)
            .param("eventType", eventType)
            .param("payload", json.write(payload))
            .param("audience", Events.audienceFor(eventType))
            .update();
    }
}
