package dev.synapse.billing;

import java.util.Set;

/**
 * Capability-only descriptor of the hosted providers (stripe, paddle, xendit,
 * paymongo) — the same {@code supports} table as the reference's provider
 * classes, so {@link BillingService} branches identically. The HTTP
 * integrations (checkout, webhooks, {@link #changePlan}) are milestone 4.
 */
public record HostedBillingProvider(String name, Set<BillingCapability> supports) implements BillingProvider {

    public static final HostedBillingProvider STRIPE = new HostedBillingProvider("stripe", Set.of(
        BillingCapability.HOSTED_CHECKOUT, BillingCapability.BILLING_PORTAL, BillingCapability.RECURRING_HOSTED,
        BillingCapability.PLAN_SYNC, BillingCapability.WEBHOOK_SIGNED));
    public static final HostedBillingProvider PADDLE = new HostedBillingProvider("paddle", Set.of(
        BillingCapability.HOSTED_CHECKOUT, BillingCapability.WEBHOOK_SIGNED));
    public static final HostedBillingProvider XENDIT = new HostedBillingProvider("xendit", Set.of(
        BillingCapability.HOSTED_CHECKOUT, BillingCapability.WEBHOOK_SIGNED));
    public static final HostedBillingProvider PAYMONGO = new HostedBillingProvider("paymongo", Set.of(
        BillingCapability.HOSTED_CHECKOUT, BillingCapability.WEBHOOK_SIGNED));

    @Override
    public SubscriptionRef changePlan(String providerSubscriptionId, ChangePlanRequest request) {
        // MILESTONE 4 SEAM: the provider HTTP call (Stripe subscription item update with proration, …).
        throw new UnsupportedOperationException(name + " plan changes through the provider API land with milestone 4");
    }
}
