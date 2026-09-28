package dev.synapse.subscriptions;

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

@Repository
public class SubscriptionRepository {

    private static final String COLUMNS = """
        id, organization_id, plan_id, status, current_period_start, current_period_end, trial_ends_at, cancel_at_period_end, canceled_at,
        provider, provider_subscription_id, billing_customer_id, plan_snapshot::text AS plan_snapshot,
        pending_adjustments::text AS pending_adjustments, created_at
        """;

    private final JdbcClient jdbc;
    private final Json json;
    private final RowMapper<Subscription> mapper;

    public SubscriptionRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new Subscription(
            Rows.uuid(rs, "id"), Rows.uuid(rs, "organization_id"), Rows.uuid(rs, "plan_id"), rs.getString("status"),
            Rows.instant(rs, "current_period_start"), Rows.instant(rs, "current_period_end"), Rows.instant(rs, "trial_ends_at"),
            rs.getBoolean("cancel_at_period_end"), Rows.instant(rs, "canceled_at"), rs.getString("provider"), rs.getString("provider_subscription_id"),
            Rows.uuid(rs, "billing_customer_id"), json.readMap(rs.getString("plan_snapshot")), json.readList(rs.getString("pending_adjustments")),
            Rows.local(rs, "created_at"));
    }

    /** The occupying subscription (trialing/active/past_due), newest first. */
    public Optional<Subscription> currentForOrg(UUID organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM subscriptions WHERE organization_id = :org AND status = ANY(:statuses) "
                + "ORDER BY created_at DESC, id DESC LIMIT 1")
            .param("org", organizationId).param("statuses", SubscriptionStateMachine.OCCUPYING_STATUSES.toArray(String[]::new))
            .query(mapper).optional();
    }

    public Optional<Subscription> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM subscriptions WHERE id = :id").param("id", id).query(mapper).optional();
    }

    public Subscription insert(UUID organizationId, UUID planId, String status, Instant periodStart, Instant periodEnd, Instant trialEndsAt,
                               String provider, String providerSubscriptionId, UUID billingCustomerId, Map<String, Object> snapshot) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO subscriptions (id, organization_id, plan_id, status, current_period_start, current_period_end, trial_ends_at,
                                           cancel_at_period_end, canceled_at, provider, provider_subscription_id, billing_customer_id,
                                           plan_snapshot, pending_adjustments, metadata)
                VALUES (:id, :org, :plan, :status, :start, :end, :trial, FALSE, NULL, :provider, :providerSub, :customer,
                        CAST(:snapshot AS jsonb), '[]'::jsonb, '{}'::jsonb)
                """)
            .param("id", id).param("org", organizationId).param("plan", planId).param("status", status)
            .param("start", Rows.at(periodStart)).param("end", Rows.at(periodEnd)).param("trial", Rows.at(trialEndsAt))
            .param("provider", provider).param("providerSub", providerSubscriptionId).param("customer", billingCustomerId)
            .param("snapshot", json.write(snapshot))
            .update();
        return findById(id).orElseThrow();
    }

    /** Persist every mutable column of the record. */
    public Subscription save(Subscription s) {
        jdbc.sql("""
                UPDATE subscriptions SET plan_id = :plan, status = :status, current_period_start = :start, current_period_end = :end,
                       trial_ends_at = :trial, cancel_at_period_end = :cancelAtEnd, canceled_at = :canceledAt, provider = :provider,
                       provider_subscription_id = :providerSub, plan_snapshot = CAST(:snapshot AS jsonb),
                       pending_adjustments = CAST(:adjustments AS jsonb), updated_at = now()
                WHERE id = :id
                """)
            .param("id", s.id()).param("plan", s.planId()).param("status", s.status())
            .param("start", Rows.at(s.currentPeriodStart())).param("end", Rows.at(s.currentPeriodEnd())).param("trial", Rows.at(s.trialEndsAt()))
            .param("cancelAtEnd", s.cancelAtPeriodEnd()).param("canceledAt", Rows.at(s.canceledAt()))
            .param("provider", s.provider()).param("providerSub", s.providerSubscriptionId())
            .param("snapshot", json.write(s.planSnapshot())).param("adjustments", json.write(List.copyOf(s.pendingAdjustments())))
            .update();
        return findById(s.id()).orElseThrow();
    }
}
