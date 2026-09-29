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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PayMongo (PH; reference: {@code providers/paymongo_provider.py}) — cards,
 * GCash and Maya through Checkout Sessions. Amounts are already minor units
 * (centavos). Webhooks use the same {@code t=…,v1=…} HMAC-SHA256 scheme as
 * Stripe, under the {@code Paymongo-Signature} header.
 */
public final class PayMongoBillingProvider implements BillingProvider {

    public static final String NAME = "paymongo";
    public static final String API_BASE = "https://api.paymongo.com/v1";
    public static final String SIGNATURE_HEADER = "paymongo-signature";
    public static final Set<BillingCapability> SUPPORTS = Set.of(BillingCapability.HOSTED_CHECKOUT, BillingCapability.WEBHOOK_SIGNED);

    private static final Duration PERIOD = Duration.ofDays(30);

    private final ProviderHttp http;
    private final ObjectMapper json;
    private final String secretKey;
    private final String webhookSecret;
    private final String currency;
    private final String apiBase;

    public PayMongoBillingProvider(ProviderHttp http, ObjectMapper json, String secretKey, String webhookSecret, String currency, String apiBase) {
        this.http = http;
        this.json = json;
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret;
        this.currency = currency;
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
        // PayMongo has no first-class customer object; we mint a stable reference.
        return new BillingCustomerRef("paymongo_" + Payloads.tokenHex(8), request.email(), request.name());
    }

    @Override
    public CheckoutResult createCheckout(CreateCheckoutRequest request) {
        Map<String, Object> lineItem = new LinkedHashMap<>();
        lineItem.put("name", request.planName());
        lineItem.put("amount", request.priceCents());
        lineItem.put("currency", request.currency());
        lineItem.put("quantity", 1);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("line_items", List.of(lineItem));
        attributes.put("metadata", Map.of("plan_key", request.planKey(),
            "org_id", request.organizationId() == null ? "" : request.organizationId().toString()));
        Map<String, Object> result = send("POST", "/checkout_sessions", Map.of("data", Map.of("attributes", attributes)));
        Map<String, Object> data = Payloads.at(result, "data");
        return new CheckoutResult(Payloads.text(Payloads.at(data, "attributes"), "checkout_url"), NAME, null, Payloads.text(data, "id"));
    }

    @Override
    public SubscriptionRef createSubscription(CreateSubscriptionRequest request) {
        return new SubscriptionRef("paymongosub_" + Payloads.tokenHex(8), "active", Instant.now().plus(PERIOD), request.providerCustomerId());
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
        return List.of(); // PayMongo surfaces payments, not invoices; we record locally
    }

    @Override
    public VerifiedWebhook verifyWebhook(WebhookRequest raw) {
        Long timestamp = null;
        String signature = null;
        for (String part : raw.header(SIGNATURE_HEADER).split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("t".equals(kv[0])) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    timestamp = null;
                }
            } else if ("v1".equals(kv[0]) && !kv[1].isEmpty()) {
                signature = kv[1];
            }
        }
        if (timestamp == null || signature == null) {
            throw new WebhookSignatureInvalidError("Malformed Paymongo-Signature header");
        }
        if (!Signatures.withinTolerance(timestamp, Instant.now().getEpochSecond())) {
            throw new WebhookSignatureInvalidError("PayMongo webhook timestamp outside tolerance");
        }
        if (!Signatures.verifySignature(raw.body(), webhookSecret, timestamp, signature)) {
            throw new WebhookSignatureInvalidError("PayMongo webhook signature mismatch");
        }
        Map<String, Object> parsed = Payloads.parse(json, raw.body(), "PayMongo");
        Map<String, Object> attributes = Payloads.at(parsed, "data", "attributes");
        String eventId = parsed.get("id") == null ? "paymongo_" + Payloads.tokenHex(8) : String.valueOf(parsed.get("id"));
        Object type = parsed.get("type") != null ? parsed.get("type") : attributes.get("type");
        return new VerifiedWebhook(eventId, type == null ? "" : String.valueOf(type), parsed, Instant.now());
    }

    @Override
    public List<NormalizedBillingEvent> translateWebhook(VerifiedWebhook verified) {
        String canonical = switch (verified.eventType()) {
            case "checkout_session.completed" -> NormalizedBillingEvent.CHECKOUT_COMPLETED;
            case "payment.paid" -> NormalizedBillingEvent.INVOICE_PAID;
            case "payment.failed" -> NormalizedBillingEvent.PAYMENT_FAILED;
            default -> null;
        };
        if (canonical == null) {
            return List.of();
        }
        Map<String, Object> attributes = Payloads.at(verified.parsed(), "data", "attributes");
        Long amountCents = null;
        if (attributes.get("line_items") instanceof List<?> items) {
            for (Object item : items) {
                Long amount = Payloads.integer(Payloads.map(item), "amount");
                if (amount != null && amount != 0) {
                    amountCents = amount;
                    break;
                }
            }
        }
        if (amountCents == null) {
            amountCents = Payloads.integer(attributes, "amount");
        }
        Map<String, Object> metadata = Payloads.at(attributes, "metadata");
        return List.of(NormalizedBillingEvent.of(canonical, verified.providerEventId(), verified.receivedAt())
            .planKey(Payloads.text(metadata, "plan_key"))
            .amountCents(amountCents)
            .currency(metadata.get("currency") == null ? currency : String.valueOf(metadata.get("currency")))
            .raw(verified.parsed())
            .build());
    }

    private Map<String, Object> send(String method, String path, Object body) {
        String basic = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        return http.json(method, apiBase + path, body, Map.of("Authorization", "Basic " + basic), "PayMongo");
    }
}
