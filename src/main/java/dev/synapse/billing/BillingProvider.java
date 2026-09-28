package dev.synapse.billing;

import java.time.Instant;
import java.util.Set;

/**
 * The provider abstraction (ADR 0004). Milestone 3 needs only the identity
 * and the capability table; the provider API surface (customers, checkout,
 * subscriptions, invoices, webhooks) lands with milestone 4 and extends this
 * interface — {@link #changePlan} is the one call the plan-change flow already reaches.
 */
public interface BillingProvider {

    String name();

    Set<BillingCapability> supports();

    record ChangePlanRequest(String planKey, long priceCents, String currency, String interval) {}

    record SubscriptionRef(String providerSubscriptionId, String status, Instant currentPeriodEnd, String providerCustomerId) {}

    /**
     * MILESTONE 4 SEAM — tell a {@link BillingCapability#RECURRING_HOSTED} provider to
     * move an existing provider subscription to a new plan. Only reached when the
     * subscription was purchased through the provider ({@code provider_subscription_id} set).
     */
    SubscriptionRef changePlan(String providerSubscriptionId, ChangePlanRequest request);
}
