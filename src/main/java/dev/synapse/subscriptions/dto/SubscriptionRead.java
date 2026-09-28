package dev.synapse.subscriptions.dto;

import dev.synapse.subscriptions.Plan;
import dev.synapse.subscriptions.Subscription;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** The contract's {@code SubscriptionRead}: the row with its plan nested. */
public record SubscriptionRead(UUID id, UUID organizationId, UUID planId, PlanRead plan, String status, Instant currentPeriodStart,
                               Instant currentPeriodEnd, Instant trialEndsAt, boolean cancelAtPeriodEnd, Instant canceledAt,
                               Map<String, Object> planSnapshot) {

    public static SubscriptionRead from(Subscription s, Plan plan) {
        return new SubscriptionRead(s.id(), s.organizationId(), s.planId(), PlanRead.from(plan), s.status(), s.currentPeriodStart(),
            s.currentPeriodEnd(), s.trialEndsAt(), s.cancelAtPeriodEnd(), s.canceledAt(), s.planSnapshot());
    }
}
