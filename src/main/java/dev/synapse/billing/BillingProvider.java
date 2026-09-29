package dev.synapse.billing;

import dev.synapse.billing.BillingRefs.BillingCustomerRef;
import dev.synapse.billing.BillingRefs.ChangePlanRequest;
import dev.synapse.billing.BillingRefs.CheckoutResult;
import dev.synapse.billing.BillingRefs.CreateCheckoutRequest;
import dev.synapse.billing.BillingRefs.CreateCustomerRequest;
import dev.synapse.billing.BillingRefs.CreateSubscriptionRequest;
import dev.synapse.billing.BillingRefs.InvoiceRef;
import dev.synapse.billing.BillingRefs.SubscriptionRef;
import java.util.List;
import java.util.Set;

/**
 * The provider abstraction (ADR 0004; reference: {@code billing/protocol.py}).
 * Services check {@link #supports()} — never provider names.
 *
 * <p>Webhook handling is split in two on purpose: {@link #verifyWebhook} is
 * transport security over the raw bytes, {@link #translateWebhook} is schema
 * mapping. Providers with exotic verification (Xendit's static token vs
 * Stripe's HMAC) differ only in the first half.
 */
public interface BillingProvider {

    String name();

    Set<BillingCapability> supports();

    BillingCustomerRef createCustomer(CreateCustomerRequest request);

    CheckoutResult createCheckout(CreateCheckoutRequest request);

    /** Providers without {@link BillingCapability#BILLING_PORTAL} never get called here. */
    default String billingPortalUrl(String providerCustomerId, String returnUrl) {
        throw new UnsupportedOperationException(name() + " does not support billing portals");
    }

    SubscriptionRef createSubscription(CreateSubscriptionRequest request);

    /**
     * Move an existing provider subscription to a new plan. Only reached for a
     * {@link BillingCapability#RECURRING_HOSTED} provider whose
     * {@code provider_subscription_id} is on the local row.
     */
    SubscriptionRef changePlan(String providerSubscriptionId, ChangePlanRequest request);

    SubscriptionRef cancelSubscription(String providerSubscriptionId, boolean atPeriodEnd);

    SubscriptionRef getSubscription(String providerSubscriptionId);

    List<InvoiceRef> listInvoices(String providerCustomerId, int limit);

    /** Signature/timestamp verification over the raw bytes; throws {@code WebhookSignatureInvalidError}. */
    VerifiedWebhook verifyWebhook(WebhookRequest raw);

    /** Parsed JSON → canonical events. An unmapped provider event yields an empty list. */
    List<NormalizedBillingEvent> translateWebhook(VerifiedWebhook verified);
}
