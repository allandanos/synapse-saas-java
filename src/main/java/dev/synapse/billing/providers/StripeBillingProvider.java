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
import dev.synapse.billing.ProviderHttp;
import dev.synapse.billing.Signatures;
import dev.synapse.billing.VerifiedWebhook;
import dev.synapse.billing.WebhookRequest;
import dev.synapse.core.errors.WebhookSignatureInvalidError;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stripe (reference: {@code providers/stripe_provider.py}) — the full
 * integration over plain HTTP, no vendor SDK: form-encoded requests against
 * {@code /v1/...} with the secret key as basic-auth user, and the documented
 * {@code Stripe-Signature: t=…,v1=…} webhook scheme.
 */
public final class StripeBillingProvider implements BillingProvider {

    public static final String NAME = "stripe";
    public static final String API_BASE = "https://api.stripe.com/v1";
    public static final String SIGNATURE_HEADER = "stripe-signature";
    public static final Set<BillingCapability> SUPPORTS = Set.of(BillingCapability.HOSTED_CHECKOUT, BillingCapability.BILLING_PORTAL,
        BillingCapability.RECURRING_HOSTED, BillingCapability.PLAN_SYNC, BillingCapability.WEBHOOK_SIGNED);

    private final ProviderHttp http;
    private final ObjectMapper json;
    private final String secretKey;
    private final String webhookSecret;
    private final String apiBase;

    public StripeBillingProvider(ProviderHttp http, ObjectMapper json, String secretKey, String webhookSecret, String apiBase) {
        this.http = http;
        this.json = json;
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret;
        this.apiBase = apiBase == null || apiBase.isBlank() ? API_BASE : apiBase;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<BillingCapability> supports() {
        return SUPPORTS;
    }

    // ── Customers / checkout ──────────────────────────────────────────────────────

    @Override
    public BillingCustomerRef createCustomer(CreateCustomerRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("email", request.email());
        data.put("name", request.name());
        data.put("metadata[org]", request.organizationId() == null ? "" : request.organizationId().toString());
        Map<String, Object> result = post("/customers", data);
        return new BillingCustomerRef(Payloads.text(result, "id"), request.email(), request.name());
    }

    @Override
    public CheckoutResult createCheckout(CreateCheckoutRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "subscription");
        data.put("line_items[0][quantity]", 1);
        data.put("line_items[0][price_data][currency]", request.currency().toLowerCase(Locale.ROOT));
        data.put("line_items[0][price_data][unit_amount]", request.priceCents());
        data.put("line_items[0][price_data][recurring][interval]", request.interval());
        data.put("line_items[0][price_data][product_data][name]", request.planName());
        data.put("metadata[plan_key]", request.planKey());
        data.put("metadata[org_id]", request.organizationId() == null ? "" : request.organizationId().toString());
        if (request.providerCustomerId() != null) {
            data.put("customer", request.providerCustomerId());
        }
        if (request.successUrl() != null) {
            data.put("success_url", request.successUrl());
        }
        if (request.cancelUrl() != null) {
            data.put("cancel_url", request.cancelUrl());
        }
        Map<String, Object> result = post("/checkout/sessions", data);
        return new CheckoutResult(Payloads.text(result, "url"), NAME, null, Payloads.text(result, "id"));
    }

    @Override
    public String billingPortalUrl(String providerCustomerId, String returnUrl) {
        Map<String, Object> result = post("/billing_portal/sessions", Map.of("customer", providerCustomerId, "return_url", returnUrl));
        return Payloads.text(result, "url");
    }

    // ── Subscriptions ─────────────────────────────────────────────────────────────

    @Override
    public SubscriptionRef createSubscription(CreateSubscriptionRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customer", request.providerCustomerId());
        data.put("items[0][price_data][currency]", request.currency().toLowerCase(Locale.ROOT));
        data.put("items[0][price_data][unit_amount]", request.priceCents());
        data.put("items[0][price_data][recurring][interval]", request.interval());
        data.put("items[0][price_data][product_data][name]", request.planKey());
        data.put("metadata[plan_key]", request.planKey());
        if (request.trialDays() > 0) {
            data.put("trial_period_days", request.trialDays());
        }
        return subscriptionRef(post("/subscriptions", data));
    }

    @Override
    public SubscriptionRef changePlan(String providerSubscriptionId, ChangePlanRequest request) {
        Map<String, Object> current = get("/subscriptions/" + providerSubscriptionId);
        String itemId = firstItemId(current);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items[0][id]", itemId);
        data.put("items[0][price_data][currency]", request.currency().toLowerCase(Locale.ROOT));
        data.put("items[0][price_data][unit_amount]", request.priceCents());
        data.put("items[0][price_data][recurring][interval]", request.interval());
        data.put("metadata[plan_key]", request.planKey());
        return subscriptionRef(post("/subscriptions/" + providerSubscriptionId, data));
    }

    @Override
    public SubscriptionRef cancelSubscription(String providerSubscriptionId, boolean atPeriodEnd) {
        if (atPeriodEnd) {
            // POST with the flag in the form body; Stripe ignores query params here.
            return subscriptionRef(post("/subscriptions/" + providerSubscriptionId, Map.of("cancel_at_period_end", true)));
        }
        return subscriptionRef(http.form("DELETE", apiBase + "/subscriptions/" + providerSubscriptionId, Map.of(), authHeaders(), "Stripe"));
    }

    @Override
    public SubscriptionRef getSubscription(String providerSubscriptionId) {
        return subscriptionRef(get("/subscriptions/" + providerSubscriptionId));
    }

    @Override
    public List<InvoiceRef> listInvoices(String providerCustomerId, int limit) {
        Map<String, Object> result = get("/invoices?customer=" + providerCustomerId + "&limit=" + limit);
        Object data = result.get("data");
        if (!(data instanceof List<?> items)) {
            return List.of();
        }
        return items.stream().map(Payloads::map).map(StripeBillingProvider::invoiceRef).toList();
    }

    /** Plan sync (CLI): create (or reuse) a product + price for a plan. */
    public Map<String, String> upsertProductAndPrice(String planKey, String planName, long priceCents, String currency, String interval) {
        Map<String, Object> product = post("/products", Map.of("name", planName, "metadata[plan_key]", planKey));
        Map<String, Object> price = post("/prices", Map.of("product", Payloads.text(product, "id"), "currency",
            currency.toLowerCase(Locale.ROOT), "unit_amount", priceCents, "recurring[interval]", interval));
        return Map.of("product_id", Payloads.text(product, "id"), "price_id", Payloads.text(price, "id"));
    }

    // ── Webhooks ──────────────────────────────────────────────────────────────────

    @Override
    public VerifiedWebhook verifyWebhook(WebhookRequest raw) {
        StripeSignature parsed = StripeSignature.parse(raw.header(SIGNATURE_HEADER));
        if (parsed == null) {
            throw new WebhookSignatureInvalidError("Malformed Stripe-Signature header");
        }
        if (!Signatures.withinTolerance(parsed.timestamp(), Instant.now().getEpochSecond())) {
            throw new WebhookSignatureInvalidError("Stripe webhook timestamp outside tolerance window");
        }
        if (!Signatures.verifySignature(raw.body(), webhookSecret, parsed.timestamp(), parsed.signature())) {
            throw new WebhookSignatureInvalidError("Stripe webhook signature mismatch");
        }
        Map<String, Object> body = Payloads.parse(json, raw.body(), "Stripe");
        return new VerifiedWebhook(String.valueOf(body.getOrDefault("id", "")), String.valueOf(body.getOrDefault("type", "")),
            body, Instant.now());
    }

    @Override
    public List<NormalizedBillingEvent> translateWebhook(VerifiedWebhook verified) {
        String eventType = verified.eventType();
        String canonical = switch (eventType) {
            case "customer.subscription.created" -> NormalizedBillingEvent.SUBSCRIPTION_CREATED;
            case "customer.subscription.updated" -> NormalizedBillingEvent.SUBSCRIPTION_UPDATED;
            case "customer.subscription.deleted" -> NormalizedBillingEvent.SUBSCRIPTION_CANCELED;
            case "invoice.paid" -> NormalizedBillingEvent.INVOICE_PAID;
            case "invoice.payment_failed" -> NormalizedBillingEvent.INVOICE_FAILED;
            case "checkout.session.completed" -> NormalizedBillingEvent.CHECKOUT_COMPLETED;
            default -> null;
        };
        if (canonical == null) {
            return List.of();
        }
        Map<String, Object> data = Payloads.at(verified.parsed(), "data", "object");
        Long created = Payloads.integer(verified.parsed(), "created");
        Instant occurredAt = created == null ? Instant.now() : Instant.ofEpochSecond(created);
        Long amount = Payloads.integer(data, "amount_paid");
        if (amount == null) {
            amount = Payloads.integer(data, "amount_due");
        }
        return List.of(NormalizedBillingEvent.of(canonical, verified.providerEventId(), occurredAt)
            .customer(Payloads.text(data, "customer"))
            .subscription(eventType.contains("subscription") ? Payloads.text(data, "id") : Payloads.text(data, "subscription"))
            .invoice(eventType.contains("invoice") ? Payloads.text(data, "id") : null)
            .planKey(Payloads.text(Payloads.at(data, "metadata"), "plan_key"))
            .status(mappedStatus(Payloads.text(data, "status")))
            .currentPeriodEnd(Payloads.epochSeconds(data, "current_period_end"))
            .amountCents(amount)
            .currency(Payloads.upperOrNull(data.get("currency")))
            .hostedUrl(Payloads.text(data, "hosted_invoice_url"))
            .raw(verified.parsed())
            .build());
    }

    /** Stripe statuses we pass through; anything else resolves to null (the reference's map lookup). */
    private static String mappedStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "trialing", "active", "past_due", "canceled", "unpaid", "incomplete" -> status;
            default -> null;
        };
    }

    /** {@code Stripe-Signature: t=…,v1=…}; {@code v1} may repeat and any match wins. */
    record StripeSignature(long timestamp, String signature) {

        static StripeSignature parse(String header) {
            Long timestamp = null;
            String signature = null;
            for (String part : header.split(",")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                if ("t".equals(kv[0])) {
                    try {
                        timestamp = Long.parseLong(kv[1]);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else if ("v1".equals(kv[0]) && !kv[1].isEmpty() && signature == null) {
                    signature = kv[1];
                }
            }
            return timestamp == null || signature == null ? null : new StripeSignature(timestamp, signature);
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────────

    private Map<String, Object> post(String path, Map<String, ?> data) {
        return http.form("POST", apiBase + path, data, authHeaders(), "Stripe");
    }

    private Map<String, Object> get(String path) {
        return http.form("GET", apiBase + path, null, authHeaders(), "Stripe");
    }

    private Map<String, String> authHeaders() {
        String basic = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        return Map.of("Authorization", "Basic " + basic);
    }

    private static String firstItemId(Map<String, Object> subscription) {
        Object data = Payloads.at(subscription, "items").get("data");
        if (data instanceof List<?> items && !items.isEmpty()) {
            return Payloads.text(Payloads.map(items.get(0)), "id");
        }
        return null;
    }

    private static SubscriptionRef subscriptionRef(Map<String, Object> data) {
        return new SubscriptionRef(Payloads.text(data, "id"), data.get("status") == null ? "active" : String.valueOf(data.get("status")),
            Payloads.epochSeconds(data, "current_period_end"), Payloads.text(data, "customer"));
    }

    private static InvoiceRef invoiceRef(Map<String, Object> data) {
        Long total = Payloads.integer(data, "total");
        Instant paidAt = Payloads.epochSeconds(Payloads.at(data, "status_transitions"), "paid_at");
        return new InvoiceRef(Payloads.text(data, "id"), Payloads.text(data, "number"),
            data.get("status") == null ? "open" : String.valueOf(data.get("status")), total == null ? 0 : total,
            data.get("currency") == null ? "PHP" : Payloads.upperOrNull(data.get("currency")), Payloads.text(data, "hosted_invoice_url"),
            Payloads.text(data, "invoice_pdf"), Payloads.epochSeconds(data, "created"), paidAt, null, null);
    }
}
