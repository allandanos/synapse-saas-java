package dev.synapse.billing;

/** What a provider can do; services check capabilities, never provider names (reference: {@code billing/protocol.py}). */
public enum BillingCapability {
    HOSTED_CHECKOUT,
    BILLING_PORTAL,
    /** Provider-side recurring subscriptions: it owns proration, renewals and invoicing. */
    RECURRING_HOSTED,
    /** Can push our catalog to the provider. */
    PLAN_SYNC,
    WEBHOOK_SIGNED,
    /** Activation may be confirmed by the tenant WITHOUT a provider callback (offline/manual payment). Manual only. */
    CLIENT_CONFIRM
}
