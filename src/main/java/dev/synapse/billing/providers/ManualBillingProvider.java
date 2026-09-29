package dev.synapse.billing.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import dev.synapse.billing.Signatures;
import dev.synapse.billing.VerifiedWebhook;
import dev.synapse.billing.WebhookRequest;
import dev.synapse.core.errors.WebhookSignatureInvalidError;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manual / enterprise provider (reference: {@code providers/manual_provider.py}).
 *
 * <p>Zero external accounts — the default, so a bare `docker compose up` gives
 * the whole freemium loop. "Checkout" is our own confirmation page; renewals are
 * advanced by the worker's recurring-billing job, which issues invoices on
 * period roll. It carries {@link BillingCapability#CLIENT_CONFIRM} because it
 * has no payment truth of its own: the operator collects out of band, so the
 * tenant's confirm is the activation signal.
 */
public final class ManualBillingProvider implements BillingProvider {

    public static final String NAME = "manual";
    /** Manual webhook ingest is protected by a shared deployment token, not a signature. */
    public static final String TOKEN_HEADER = "x-manual-token";
    public static final Set<BillingCapability> SUPPORTS =
        Set.of(BillingCapability.HOSTED_CHECKOUT, BillingCapability.CLIENT_CONFIRM);

    private static final Duration PERIOD = Duration.ofDays(30);

    private final ObjectMapper json;
    private final String webhookToken;
    private final String currency;

    public ManualBillingProvider(ObjectMapper json, String webhookToken, String currency) {
        this.json = json;
        this.webhookToken = webhookToken == null ? "" : webhookToken;
        this.currency = currency;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<BillingCapability> supports() {
        return SUPPORTS;
    }

    public String currency() {
        return currency;
    }

    @Override
    public BillingCustomerRef createCustomer(CreateCustomerRequest request) {
        return new BillingCustomerRef("manual_" + Payloads.tokenHex(8), request.email(), request.name());
    }

    @Override
    public CheckoutResult createCheckout(CreateCheckoutRequest request) {
        // Manual checkout renders our own confirmation page; there is no external URL.
        String instructions = "Confirm the " + request.planName() + " plan ("
            + Payloads.money(request.priceCents(), request.currency()) + "/" + request.interval() + "). "
            + "No payment provider is configured; the subscription activates immediately "
            + "and invoices are recorded by the system.";
        return new CheckoutResult(null, NAME, instructions, "manualco_" + Payloads.tokenHex(8));
    }

    @Override
    public SubscriptionRef createSubscription(CreateSubscriptionRequest request) {
        return new SubscriptionRef("manualsub_" + Payloads.tokenHex(8), "active", Instant.now().plus(PERIOD), request.providerCustomerId());
    }

    @Override
    public SubscriptionRef changePlan(String providerSubscriptionId, ChangePlanRequest request) {
        return new SubscriptionRef(providerSubscriptionId, "active", Instant.now().plus(PERIOD), null);
    }

    @Override
    public SubscriptionRef cancelSubscription(String providerSubscriptionId, boolean atPeriodEnd) {
        return SubscriptionRef.of(providerSubscriptionId, "canceled");
    }

    @Override
    public SubscriptionRef getSubscription(String providerSubscriptionId) {
        return new SubscriptionRef(providerSubscriptionId, "active", Instant.now().plus(PERIOD), null);
    }

    @Override
    public List<InvoiceRef> listInvoices(String providerCustomerId, int limit) {
        return List.of(); // manual invoices live in our database only
    }

    @Override
    public VerifiedWebhook verifyWebhook(WebhookRequest raw) {
        String token = raw.header(TOKEN_HEADER);
        if (webhookToken.isEmpty() || !Signatures.constantTimeEquals(token, webhookToken)) {
            throw new WebhookSignatureInvalidError("Missing or invalid manual webhook token");
        }
        Map<String, Object> parsed = Payloads.parse(json, raw.body(), "manual");
        String eventId = parsed.get("id") == null ? "manual_" + Payloads.tokenHex(8) : String.valueOf(parsed.get("id"));
        String eventType = parsed.get("type") == null ? "manual.event" : String.valueOf(parsed.get("type"));
        return new VerifiedWebhook(eventId, eventType, parsed, Instant.now());
    }

    @Override
    public List<NormalizedBillingEvent> translateWebhook(VerifiedWebhook verified) {
        String canonical = switch (String.valueOf(verified.parsed().getOrDefault("type", ""))) {
            case "manual.subscription.activated" -> NormalizedBillingEvent.SUBSCRIPTION_ACTIVATED;
            case "manual.subscription.canceled" -> NormalizedBillingEvent.SUBSCRIPTION_CANCELED;
            case "manual.invoice.paid" -> NormalizedBillingEvent.INVOICE_PAID;
            default -> null;
        };
        if (canonical == null) {
            return List.of();
        }
        Map<String, Object> data = Payloads.at(verified.parsed(), "data");
        return List.of(NormalizedBillingEvent.of(canonical, verified.providerEventId(), verified.receivedAt())
            .subscription(Payloads.text(data, "subscription_id"))
            .customer(Payloads.text(data, "customer_id"))
            .planKey(Payloads.text(data, "plan_key"))
            .status(Payloads.text(data, "status"))
            .amountCents(Payloads.integer(data, "amount_cents"))
            .currency(Payloads.text(data, "currency"))
            .raw(verified.parsed())
            .build());
    }
}
