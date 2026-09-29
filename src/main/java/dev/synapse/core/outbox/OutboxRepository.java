package dev.synapse.core.outbox;

import dev.synapse.core.db.Json;
import dev.synapse.core.db.Rows;
import dev.synapse.core.ids.Ids;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code outbox_events} as the worker's dispatcher sees it: claim, publish, retry, dead-letter. */
@Repository
public class OutboxRepository {

    /** A pending row, with everything the dispatcher needs to fan it out. */
    public record OutboxEvent(UUID id, String aggregateType, UUID aggregateId, UUID organizationId, String eventType,
                              Map<String, Object> payload, String audience, int attempts) {}

    private final JdbcClient jdbc;
    private final Json json;
    private final RowMapper<OutboxEvent> mapper;

    public OutboxRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new OutboxEvent(Rows.uuid(rs, "id"), rs.getString("aggregate_type"), Rows.uuid(rs, "aggregate_id"),
            Rows.uuid(rs, "organization_id"), rs.getString("event_type"), json.readMap(rs.getString("payload")),
            rs.getString("audience"), rs.getInt("attempts"));
    }

    /** Due, undelivered, not dead-lettered — claimed for this transaction only. */
    public List<UUID> claimPending(int limit) {
        return jdbc.sql("""
                SELECT id FROM outbox_events
                WHERE published_at IS NULL AND dead_at IS NULL AND next_attempt_at <= now()
                ORDER BY id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
            .param("limit", limit).query(UUID.class).list();
    }

    public Optional<OutboxEvent> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, aggregate_type, aggregate_id, organization_id, event_type, payload::text AS payload, audience, attempts
                FROM outbox_events WHERE id = :id
                """)
            .param("id", id).query(mapper).optional();
    }

    public void markPublished(UUID id, Instant publishedAt) {
        jdbc.sql("UPDATE outbox_events SET published_at = :publishedAt WHERE id = :id")
            .param("id", id).param("publishedAt", Rows.at(publishedAt)).update();
    }

    /** Retry bookkeeping; {@code deadAt} set once the attempts are spent. */
    public void markFailure(UUID id, int attempts, String lastError, Instant nextAttemptAt, Instant deadAt) {
        jdbc.sql("""
                UPDATE outbox_events SET attempts = :attempts, last_error = :error,
                       next_attempt_at = COALESCE(:nextAttempt, next_attempt_at), dead_at = :deadAt
                WHERE id = :id
                """)
            .param("id", id).param("attempts", attempts).param("error", lastError)
            .param("nextAttempt", Rows.at(nextAttemptAt)).param("deadAt", Rows.at(deadAt))
            .update();
    }

    /** Append from a worker job (no request transaction): same shape as {@link OutboxWriter}. */
    public void append(String eventType, String aggregateType, UUID aggregateId, UUID organizationId, Map<String, Object> payload) {
        jdbc.sql("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, organization_id, event_type, payload, attempts, audience)
                VALUES (:id, :aggregateType, :aggregateId, :organizationId, :eventType, CAST(:payload AS jsonb), 0, :audience)
                """)
            .param("id", Ids.uuidV7()).param("aggregateType", aggregateType).param("aggregateId", aggregateId)
            .param("organizationId", organizationId).param("eventType", eventType).param("payload", json.write(payload))
            .param("audience", Events.audienceFor(eventType))
            .update();
    }
}
