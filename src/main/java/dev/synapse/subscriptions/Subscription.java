package dev.synapse.subscriptions;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Row of {@code subscriptions}. {@code planSnapshot} freezes purchase-time
 * pricing/features (grandfathering); {@code pendingAdjustments} queues prorated
 * credits/charges for the next period invoice. Immutable: every change is a wither.
 */
public record Subscription(UUID id, UUID organizationId, UUID planId, String status, Instant currentPeriodStart,
                           Instant currentPeriodEnd, Instant trialEndsAt, boolean cancelAtPeriodEnd, Instant canceledAt,
                           String provider, String providerSubscriptionId, UUID billingCustomerId,
                           Map<String, Object> planSnapshot, List<Map<String, Object>> pendingAdjustments, LocalDateTime createdAt) {

    public Subscription {
        planSnapshot = Map.copyOf(planSnapshot);
        pendingAdjustments = List.copyOf(pendingAdjustments);
    }

    public Subscription withStatus(String newStatus) {
        return new Subscription(id, organizationId, planId, newStatus, currentPeriodStart, currentPeriodEnd, trialEndsAt,
            cancelAtPeriodEnd, canceledAt, provider, providerSubscriptionId, billingCustomerId, planSnapshot, pendingAdjustments, createdAt);
    }

    public Subscription withPlan(UUID newPlanId, Map<String, Object> newSnapshot) {
        return new Subscription(id, organizationId, newPlanId, status, currentPeriodStart, currentPeriodEnd, trialEndsAt,
            cancelAtPeriodEnd, canceledAt, provider, providerSubscriptionId, billingCustomerId, newSnapshot, pendingAdjustments, createdAt);
    }

    public Subscription withPeriod(Instant start, Instant end) {
        return new Subscription(id, organizationId, planId, status, start, end, trialEndsAt,
            cancelAtPeriodEnd, canceledAt, provider, providerSubscriptionId, billingCustomerId, planSnapshot, pendingAdjustments, createdAt);
    }

    public Subscription withPeriodEnd(Instant end) {
        return withPeriod(currentPeriodStart, end);
    }

    public Subscription withCancellation(boolean atPeriodEnd, Instant at) {
        return new Subscription(id, organizationId, planId, status, currentPeriodStart, currentPeriodEnd, trialEndsAt,
            atPeriodEnd, at, provider, providerSubscriptionId, billingCustomerId, planSnapshot, pendingAdjustments, createdAt);
    }

    public Subscription withProvider(String newProvider, String newProviderSubscriptionId) {
        return new Subscription(id, organizationId, planId, status, currentPeriodStart, currentPeriodEnd, trialEndsAt,
            cancelAtPeriodEnd, canceledAt, newProvider, newProviderSubscriptionId, billingCustomerId, planSnapshot, pendingAdjustments, createdAt);
    }

    public Subscription withPendingAdjustments(List<Map<String, Object>> adjustments) {
        return new Subscription(id, organizationId, planId, status, currentPeriodStart, currentPeriodEnd, trialEndsAt,
            cancelAtPeriodEnd, canceledAt, provider, providerSubscriptionId, billingCustomerId, planSnapshot, adjustments, createdAt);
    }

    /** The plan key frozen at purchase time. */
    public String snapshotKey() {
        Object key = planSnapshot.get("key");
        return key == null ? null : String.valueOf(key);
    }
}
