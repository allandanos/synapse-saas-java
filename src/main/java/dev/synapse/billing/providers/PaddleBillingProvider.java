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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Paddle Billing (reference: {@code providers/paddle_provider.py}) — hosted
 * checkout plus webhook ingest; recurring is scheduler-backed like
 * Xendit/PayMongo, so it carries no {@code recurring_hosted} flag and the
 * worker bills it locally.
 *
 * <p>Webhooks: {@code Paddle-Signature: ts=<unix>;h1=<hex>} (semicolon
 * separated, a comma tolerated for proxies that rewrite it) over
 * {@code "<ts>:<raw body>"} — a colon, not Stripe's dot.
 */
public final class PaddleBillingProvider implements BillingProvider {

    public static final String NAME = "paddle";
    public static final String API_BASE = "https://api.paddle.com";
    public static final String SIGNATURE_HEADER = "paddle-signature";
    public static final Set<BillingCapability> SUPPORTS = Set.of(BillingCapability.HOSTED_CHECKOUT, BillingCapability.WEBHOOK_SIGNED);

    private static final Duration PERIOD = Duration.ofDays(30);

    private final ProviderHttp http;
    private final ObjectMapper json;
    private final String secretKey;
    private final String webhookSecret;
    private final String apiBase;

    public PaddleBillingProvider(ProviderHttp http, ObjectMapper json, String secretKey, String webhookSecret, String apiBase) {
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

    @Override
    public BillingCustomerRef createCustomer(CreateCustomerRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", request.email());
        body.put("name", request.name());
        Map<String, Object> data = dataOf(post("/customers", body));
        return new BillingCustomerRef(String.valueOf(data.getOrDefault("id", "")), request.email(), request.name());
    }

    @Override
    public CheckoutResult createCheckout(CreateCheckoutRequest request) {
        // Paddle prices live in the dashboard; we reference the plan through
        // custom_data and use a one-off items shape for dynamic pricing.
        Map<String, Object> price = new LinkedHashMap<>();
        price.put("unit_amount", request.priceCents());
        price.put("currency_code", request.currency().toLowerCase(Locale.ROOT));
        price.put("product", Map.of("name", request.planName()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(Map.of("price", price, "quantity", 1)));
        body.put("custom_data", Map.of("plan_key", request.planKey(),
            "org_id", request.organizationId() == null ? "" : request.organizationId().toString()));
        Map<String, Object> data = dataOf(post("/transactions", body));
        String url = data.get("checkout") instanceof Map<?, ?> ? Payloads.text(Payloads.at(data, "checkout"), "url") : null;
        return new CheckoutResult(url, NAME, null, String.valueOf(data.getOrDefault("id", "")));
    }

    // ── Subscriptions (scheduler-backed, like Xendit/PayMongo) ─────────────────────

    @Override
    public SubscriptionRef createSubscription(CreateSubscriptionRequest request) {
        return new SubscriptionRef("paddlesub_" + Payloads.tokenHex(8), "active", Instant.now().plus(PERIOD), request.providerCustomerId());
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
        return List.of();
    }

    // ── Webhooks ──────────────────────────────────────────────────────────────────

    @Override
    public VerifiedWebhook verifyWebhook(WebhookRequest raw) {
        Map<String, Object> parsed = Payloads.parse(json, raw.body(), "Paddle");
        String header = raw.header(SIGNATURE_HEADER);
        if (!header.isEmpty()) {
            Map<String, String> parts = new LinkedHashMap<>();
            for (String part : header.split("[;,]")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2) {
                    parts.put(kv[0], kv[1]);
                }
            }
            String ts = parts.get("ts");
            String h1 = parts.get("h1");
            if (ts != null && h1 != null && !webhookSecret.isEmpty()) {
                long timestamp;
                try {
                    timestamp = Long.parseLong(ts);
                } catch (NumberFormatException e) {
                    throw new WebhookSignatureInvalidError("Bad Paddle timestamp");
                }
                if (!Signatures.withinTolerance(timestamp, Instant.now().getEpochSecond())) {
                    throw new WebhookSignatureInvalidError("Paddle webhook timestamp outside tolerance");
                }
                if (Signatures.constantTimeEquals(Signatures.signPaddle(raw.body(), webhookSecret, timestamp), h1)) {
                    String eventId = parsed.get("event_id") == null ? "paddle_" + Payloads.tokenHex(8) : String.valueOf(parsed.get("event_id"));
                    return new VerifiedWebhook(eventId, String.valueOf(parsed.getOrDefault("event_type", "")), parsed, Instant.now());
                }
            }
        }
        if (webhookSecret.isEmpty()) {
            throw new WebhookSignatureInvalidError("Paddle webhook secret not configured");
        }
        throw new WebhookSignatureInvalidError("Paddle webhook signature mismatch");
    }

    @Override
    public List<NormalizedBillingEvent> translateWebhook(VerifiedWebhook verified) {
        String eventType = verified.eventType();
        String canonical = switch (eventType) {
            case "transaction.completed" -> NormalizedBillingEvent.CHECKOUT_COMPLETED;
            case "subscription.activated" -> NormalizedBillingEvent.SUBSCRIPTION_ACTIVATED;
            case "subscription.updated" -> NormalizedBillingEvent.SUBSCRIPTION_UPDATED;
            case "subscription.canceled" -> NormalizedBillingEvent.SUBSCRIPTION_CANCELED;
            case "subscription.past_due" -> NormalizedBillingEvent.SUBSCRIPTION_PAST_DUE;
            default -> null;
        };
        if (canonical == null) {
            return List.of();
        }
        Map<String, Object> data = Payloads.at(verified.parsed(), "data");
        Map<String, Object> custom = Payloads.at(data, "custom_data");
        Long amount = data.get("totals") instanceof Map<?, ?> ? Payloads.integer(Payloads.at(data, "totals"), "total") : null;
        return List.of(NormalizedBillingEvent.of(canonical, verified.providerEventId(), verified.receivedAt())
            .customer(Payloads.text(data, "customer_id"))
            .subscription(eventType.contains("subscription") ? Payloads.text(data, "id") : Payloads.text(data, "subscription_id"))
            .planKey(Payloads.text(custom, "plan_key"))
            .status(Payloads.text(data, "status"))
            .amountCents(amount)
            .currency(Payloads.upperOrNull(data.get("currency_code")))
            .raw(verified.parsed())
            .build());
    }

    private Map<String, Object> post(String path, Object body) {
        return http.json("POST", apiBase + path, body, Map.of("Authorization", "Bearer " + secretKey), "Paddle");
    }

    /** Paddle wraps every response in {@code data}; older shapes return it flat. */
    private static Map<String, Object> dataOf(Map<String, Object> result) {
        return result.containsKey("data") ? Payloads.at(result, "data") : result;
    }
}
