package dev.synapse.billing;

import java.time.Instant;
import java.util.UUID;

/**
 * Provider-side references and request DTOs
 * (reference: {@code billing/protocol.py}). Money is integer minor units end to
 * end (ADR 0006) — never a float multiplied by 100.
 */
public final class BillingRefs {

    private BillingRefs() {}

    public record BillingCustomerRef(String providerCustomerId, String email, String name) {}

    /** Hosted-checkout URL, or manual instructions for off-provider flows. */
    public record CheckoutResult(String url, String provider, String manualInstructions, String providerCheckoutId) {}

    public record SubscriptionRef(String providerSubscriptionId, String status, Instant currentPeriodEnd, String providerCustomerId) {

        public static SubscriptionRef of(String id, String status) {
            return new SubscriptionRef(id, status, null, null);
        }
    }

    public record InvoiceRef(String providerInvoiceId, String number, String status, long totalCents, String currency, String hostedUrl,
                             String pdfUrl, Instant issuedAt, Instant paidAt, Instant periodStart, Instant periodEnd) {}

    public record CreateCustomerRequest(String email, String name, UUID organizationId, String currency) {}

    public record CreateCheckoutRequest(String planKey, String planName, long priceCents, String currency, String interval,
                                        String providerCustomerId, String successUrl, String cancelUrl, UUID organizationId) {}

    public record CreateSubscriptionRequest(String planKey, long priceCents, String currency, String interval, String providerCustomerId,
                                            int trialDays) {}

    public record ChangePlanRequest(String planKey, long priceCents, String currency, String interval) {}
}
