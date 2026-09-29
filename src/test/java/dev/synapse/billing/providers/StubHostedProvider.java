package dev.synapse.billing.providers;

import dev.synapse.billing.BillingCapability;
import dev.synapse.billing.BillingProvider;
import dev.synapse.billing.BillingRefs.BillingCustomerRef;
import dev.synapse.billing.BillingRefs.ChangePlanRequest;
import dev.synapse.billing.BillingRefs.CheckoutResult;
import dev.synapse.billing.BillingRefs.CreateCheckoutRequest;
import dev.synapse.billing.BillingRefs.CreateCustomerRequest;
import dev.synapse.billing.BillingRefs.CreateSubscriptionRequest;
import dev.synapse.billing.BillingRefs.InvoiceRef;
import dev.synapse.billing.BillingRefs.SubscriptionRef;
import dev.synapse.billing.NormalizedBillingEvent;
import dev.synapse.billing.VerifiedWebhook;
import dev.synapse.billing.WebhookRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A provider that bills recurring on its side (Stripe's capability set) without
 * an HTTP call — the seam {@code BillingService.changePlan} reaches when the
 * subscription was purchased through the provider.
 */
public final class StubHostedProvider implements BillingProvider {

    public static final String NAME = "stub_hosted";

    private final List<String> changed = new ArrayList<>();

    /** {@code "<providerSubscriptionId>:<planKey>"} per call, in order. */
    public List<String> changed() {
        return List.copyOf(changed);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<BillingCapability> supports() {
        return Set.of(BillingCapability.HOSTED_CHECKOUT, BillingCapability.BILLING_PORTAL, BillingCapability.RECURRING_HOSTED,
            BillingCapability.WEBHOOK_SIGNED);
    }

    @Override
    public BillingCustomerRef createCustomer(CreateCustomerRequest request) {
        return new BillingCustomerRef("cus_stub", request.email(), request.name());
    }

    @Override
    public CheckoutResult createCheckout(CreateCheckoutRequest request) {
        return new CheckoutResult("https://stub.example.com/checkout", NAME, null, "cs_stub");
    }

    @Override
    public String billingPortalUrl(String providerCustomerId, String returnUrl) {
        return "https://stub.example.com/portal";
    }

    @Override
    public SubscriptionRef createSubscription(CreateSubscriptionRequest request) {
        return new SubscriptionRef("sub_stub", "active", Instant.now().plus(30, ChronoUnit.DAYS), request.providerCustomerId());
    }

    @Override
    public SubscriptionRef changePlan(String providerSubscriptionId, ChangePlanRequest request) {
        changed.add(providerSubscriptionId + ":" + request.planKey());
        return new SubscriptionRef(providerSubscriptionId, "active", Instant.now().plus(30, ChronoUnit.DAYS), "cus_stub");
    }

    @Override
    public SubscriptionRef cancelSubscription(String providerSubscriptionId, boolean atPeriodEnd) {
        return SubscriptionRef.of(providerSubscriptionId, "canceled");
    }

    @Override
    public SubscriptionRef getSubscription(String providerSubscriptionId) {
        return SubscriptionRef.of(providerSubscriptionId, "active");
    }

    @Override
    public List<InvoiceRef> listInvoices(String providerCustomerId, int limit) {
        return List.of();
    }

    @Override
    public VerifiedWebhook verifyWebhook(WebhookRequest raw) {
        throw new UnsupportedOperationException("the stub provider does not ingest webhooks");
    }

    @Override
    public List<NormalizedBillingEvent> translateWebhook(VerifiedWebhook verified) {
        return List.of();
    }
}
