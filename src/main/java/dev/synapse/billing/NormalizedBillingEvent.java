package dev.synapse.billing;

import java.time.Instant;
import java.util.Map;

/**
 * Canonical, provider-agnostic billing event
 * (reference: {@code billing/protocol.py:NormalizedBillingEvent}).
 * Providers map their vocabulary onto the constants below; nothing downstream
 * ever sees a provider's own event name.
 */
public record NormalizedBillingEvent(String eventType, String providerEventId, Instant occurredAt, String providerCustomerId,
                                     String providerSubscriptionId, String providerInvoiceId, String planKey, String status,
                                     Instant currentPeriodEnd, Long amountCents, String currency, String hostedUrl,
                                     Map<String, Object> raw) {

    // Canonical event vocabulary
    public static final String CUSTOMER_CREATED = "customer.created";
    public static final String SUBSCRIPTION_CREATED = "subscription.created";
    public static final String SUBSCRIPTION_ACTIVATED = "subscription.activated";
    public static final String SUBSCRIPTION_UPDATED = "subscription.updated";
    public static final String SUBSCRIPTION_CANCELED = "subscription.canceled";
    public static final String SUBSCRIPTION_PAST_DUE = "subscription.past_due";
    public static final String SUBSCRIPTION_TRIAL_ENDED = "subscription.trial_ended";
    public static final String INVOICE_CREATED = "invoice.created";
    public static final String INVOICE_PAID = "invoice.paid";
    public static final String INVOICE_FAILED = "invoice.failed";
    public static final String CHECKOUT_COMPLETED = "checkout.completed";
    public static final String PAYMENT_FAILED = "payment.failed";

    public NormalizedBillingEvent {
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    public static Builder of(String eventType, String providerEventId, Instant occurredAt) {
        return new Builder(eventType, providerEventId, occurredAt);
    }

    /** The reference's keyword defaults: everything except the three required fields is optional. */
    public static final class Builder {
        private final String eventType;
        private final String providerEventId;
        private final Instant occurredAt;
        private String providerCustomerId;
        private String providerSubscriptionId;
        private String providerInvoiceId;
        private String planKey;
        private String status;
        private Instant currentPeriodEnd;
        private Long amountCents;
        private String currency;
        private String hostedUrl;
        private Map<String, Object> raw = Map.of();

        private Builder(String eventType, String providerEventId, Instant occurredAt) {
            this.eventType = eventType;
            this.providerEventId = providerEventId;
            this.occurredAt = occurredAt;
        }

        public Builder customer(String value) { this.providerCustomerId = value; return this; }
        public Builder subscription(String value) { this.providerSubscriptionId = value; return this; }
        public Builder invoice(String value) { this.providerInvoiceId = value; return this; }
        public Builder planKey(String value) { this.planKey = value; return this; }
        public Builder status(String value) { this.status = value; return this; }
        public Builder currentPeriodEnd(Instant value) { this.currentPeriodEnd = value; return this; }
        public Builder amountCents(Long value) { this.amountCents = value; return this; }
        public Builder currency(String value) { this.currency = value; return this; }
        public Builder hostedUrl(String value) { this.hostedUrl = value; return this; }
        public Builder raw(Map<String, Object> value) { this.raw = value; return this; }

        public NormalizedBillingEvent build() {
            return new NormalizedBillingEvent(eventType, providerEventId, occurredAt, providerCustomerId, providerSubscriptionId,
                providerInvoiceId, planKey, status, currentPeriodEnd, amountCents, currency, hostedUrl, raw);
        }
    }
}
