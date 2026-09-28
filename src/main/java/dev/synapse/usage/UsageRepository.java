package dev.synapse.usage;

import dev.synapse.core.db.Rows;
import dev.synapse.core.ids.Ids;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code usage_events} (partitioned, append-only), {@code usage_counters} (one row per org/metric/period) and the idempotency ledger. */
@Repository
public class UsageRepository {

    public record CounterRow(String metric, long quantityTotal) {}

    public record IdempotencyRow(String metric, long quantity, Long totalAfter) {}

    private final JdbcClient jdbc;

    public UsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── counters ─────────────────────────────────────────────────────────────────

    public long total(UUID organizationId, String metric, LocalDate period) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity_total), 0) FROM usage_counters WHERE organization_id = :org AND metric = :metric AND period_start = :period")
            .param("org", organizationId).param("metric", metric).param("period", period).query(Long.class).single();
    }

    /** Counter rows of the month plus the gauge bucket (the console summary). */
    public List<CounterRow> summary(UUID organizationId, LocalDate period, LocalDate gaugePeriod) {
        return jdbc.sql("""
                SELECT metric, quantity_total FROM usage_counters
                WHERE organization_id = :org AND (period_start = :period OR period_start = :gauge)
                ORDER BY metric
                """)
            .param("org", organizationId).param("period", period).param("gauge", gaugePeriod)
            .query((rs, i) -> new CounterRow(rs.getString("metric"), rs.getLong("quantity_total"))).list();
    }

    /** Atomic upsert-increment; the row lock serialises concurrent consumers. Returns the new total. */
    public long increment(UUID organizationId, String metric, long quantity, LocalDate period, Instant occurredAt) {
        return jdbc.sql("""
                INSERT INTO usage_counters (organization_id, metric, period_start, quantity_total, last_event_at)
                VALUES (:org, :metric, :period, :qty, :occurred)
                ON CONFLICT (organization_id, metric, period_start)
                DO UPDATE SET quantity_total = usage_counters.quantity_total + EXCLUDED.quantity_total, last_event_at = EXCLUDED.last_event_at
                RETURNING quantity_total
                """)
            .param("org", organizationId).param("metric", metric).param("period", period).param("qty", quantity).param("occurred", Rows.at(occurredAt))
            .query(Long.class).single();
    }

    public void setLevel(UUID organizationId, String metric, LocalDate period, long level) {
        jdbc.sql("""
                INSERT INTO usage_counters (organization_id, metric, period_start, quantity_total, last_event_at)
                VALUES (:org, :metric, :period, :level, now())
                ON CONFLICT (organization_id, metric, period_start)
                DO UPDATE SET quantity_total = EXCLUDED.quantity_total, last_event_at = now()
                """)
            .param("org", organizationId).param("metric", metric).param("period", period).param("level", level).update();
    }

    /** Move a level by {@code delta}, never below zero; returns the new level. */
    public long adjustLevel(UUID organizationId, String metric, LocalDate period, long delta) {
        return jdbc.sql("""
                INSERT INTO usage_counters (organization_id, metric, period_start, quantity_total, last_event_at)
                VALUES (:org, :metric, :period, GREATEST(:delta, 0), now())
                ON CONFLICT (organization_id, metric, period_start)
                DO UPDATE SET quantity_total = GREATEST(usage_counters.quantity_total + :delta, 0), last_event_at = now()
                RETURNING quantity_total
                """)
            .param("org", organizationId).param("metric", metric).param("period", period).param("delta", delta).query(Long.class).single();
    }

    /** True the first time the soft limit is crossed in a period (the flag guards exactly-once notification). */
    public boolean markSoftLimitNotified(UUID organizationId, String metric, LocalDate period) {
        return jdbc.sql("""
                UPDATE usage_counters SET soft_limit_notified_at = now()
                WHERE organization_id = :org AND metric = :metric AND period_start = :period AND soft_limit_notified_at IS NULL
                RETURNING 1
                """)
            .param("org", organizationId).param("metric", metric).param("period", period).query(Integer.class).optional().isPresent();
    }

    // ── events ───────────────────────────────────────────────────────────────────

    public UUID insertEvent(UUID organizationId, String metric, long quantity, Instant occurredAt, String idempotencyKey, String propertiesJson) {
        UUID id = Ids.uuidV7();
        jdbc.sql("""
                INSERT INTO usage_events (id, organization_id, metric, quantity, occurred_at, idempotency_key, properties)
                VALUES (:id, :org, :metric, :qty, :occurred, :idem, CAST(:props AS jsonb))
                """)
            .param("id", id).param("org", organizationId).param("metric", metric).param("qty", quantity).param("occurred", Rows.at(occurredAt))
            .param("idem", idempotencyKey).param("props", propertiesJson)
            .update();
        return id;
    }

    // ── idempotency ledger ───────────────────────────────────────────────────────

    /** True ⇒ first sight of this key (the caller proceeds); false ⇒ replay. A concurrent retry blocks here until the first commits. */
    public boolean reserve(UUID organizationId, String key, String metric, long quantity) {
        return jdbc.sql("""
                INSERT INTO usage_idempotency_keys (organization_id, idempotency_key, metric, quantity)
                VALUES (:org, :key, :metric, :qty)
                ON CONFLICT (organization_id, idempotency_key) DO NOTHING
                RETURNING 1
                """)
            .param("org", organizationId).param("key", key).param("metric", metric).param("qty", quantity)
            .query(Integer.class).optional().isPresent();
    }

    public void settle(UUID organizationId, String key, UUID eventId, long total) {
        jdbc.sql("UPDATE usage_idempotency_keys SET event_id = :event, total_after = :total WHERE organization_id = :org AND idempotency_key = :key")
            .param("event", eventId).param("total", total).param("org", organizationId).param("key", key).update();
    }

    public Optional<IdempotencyRow> replay(UUID organizationId, String key) {
        return jdbc.sql("SELECT metric, quantity, total_after FROM usage_idempotency_keys WHERE organization_id = :org AND idempotency_key = :key")
            .param("org", organizationId).param("key", key)
            .query((rs, i) -> new IdempotencyRow(rs.getString("metric"), rs.getLong("quantity"), Rows.longOrNull(rs, "total_after"))).optional();
    }
}
