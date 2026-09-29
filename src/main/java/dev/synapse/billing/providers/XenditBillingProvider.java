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
import dev.synapse.core.errors.BillingProviderError;
import dev.synapse.core.errors.WebhookSignatureInvalidError;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Xendit (PH; reference: {@code providers/xendit_provider.py}) — invoice-cycle
 * billing rather than a Stripe-style subscription object, so recurring is left
 * to the framework's scheduler. Webhooks are authenticated by a static
 * {@code X-Callback-Token} compared in constant time.
 *
 * <p>Money: Xendit takes and reports MAJOR units, so amounts are converted with
 * exact decimal arithmetic — never {@code (int)(float * 100)} (ADR 0006).
 */
public final class XenditBillingProvider implements BillingProvider {

    public static final String NAME = "xendit";
    public static final String API_BASE = "https://api.xendit.co";
    public static final String TOKEN_HEADER = "x-callback-token";
    public static final Set<BillingCapability> SUPPORTS =
        Set.of(BillingCapability.HOSTED_CHECKOUT, BillingCapability.WEBHOOK_SIGNED); // token-authenticated

    private static final Duration PERIOD = Duration.ofDays(30);
    private static final BigDecimal MINOR_UNITS = BigDecimal.valueOf(100);

    private final ProviderHttp http;
    private final ObjectMapper json;
    private final String secretKey;
    private final String webhookToken;
    private final String currency;
    private final String apiBase;

    public XenditBillingProvider(ProviderHttp http, ObjectMapper json, String secretKey, String webhookToken, String currency, String apiBase) {
        this.http = http;
        this.json = json;
        this.secretKey = secretKey;
        this.webhookToken = webhookToken == null ? "" : webhookToken;
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference_id", request.organizationId() == null ? Payloads.tokenHex(8) : request.organizationId().toString());
        body.put("email", request.email());
        body.put("given_names", request.name());
        Map<String, Object> result = send("POST", "/customers", body);
        return new BillingCustomerRef(Payloads.text(result, "id"), request.email(), request.name());
    }

    @Override
    public CheckoutResult createCheckout(CreateCheckoutRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("external_id", "synapse_" + request.planKey() + "_" + Payloads.tokenHex(4));
        body.put("amount", majorUnits(request.priceCents()));
        body.put("currency", request.currency());
        body.put("description", request.planName() + " (" + request.interval() + "ly)");
        body.put("success_redirect_url", request.successUrl());
        body.put("failure_redirect_url", request.cancelUrl());
        Map<String, Object> result = send("POST", "/invoices", body);
        return new CheckoutResult(Payloads.text(result, "invoice_url"), NAME, null, Payloads.text(result, "id"));
    }

    // ── Subscriptions: a scheduled invoice cycle; the framework renews ─────────────

    @Override
    public SubscriptionRef createSubscription(CreateSubscriptionRequest request) {
        return new SubscriptionRef("xenditsub_" + Payloads.tokenHex(8), "active", Instant.now().plus(PERIOD), request.providerCustomerId());
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
        Map<String, Object> result = send("GET", "/v2/invoices?limit=" + limit, null);
        Object data = result.get("data");
        if (!(data instanceof List<?> items)) {
            return List.of();
        }
        return items.stream().map(Payloads::map).map(this::invoiceRef).toList();
    }

    // ── Webhooks ──────────────────────────────────────────────────────────────────

    @Override
    public VerifiedWebhook verifyWebhook(WebhookRequest raw) {
        String token = raw.header(TOKEN_HEADER);
        if (webhookToken.isEmpty() || !Signatures.constantTimeEquals(token, webhookToken)) {
            throw new WebhookSignatureInvalidError("Missing or invalid X-Callback-Token");
        }
        Map<String, Object> parsed = Payloads.parse(json, raw.body(), "Xendit");
        String eventId = parsed.get("id") == null ? "xendit_" + Payloads.tokenHex(8) : String.valueOf(parsed.get("id"));
        // Xendit has no event-type field: the invoice STATUS is the event.
        return new VerifiedWebhook(eventId, String.valueOf(parsed.getOrDefault("status", "")), parsed, Instant.now());
    }

    @Override
    public List<NormalizedBillingEvent> translateWebhook(VerifiedWebhook verified) {
        String canonical = switch (verified.eventType()) {
            case "PAID" -> NormalizedBillingEvent.INVOICE_PAID;
            case "EXPIRED" -> NormalizedBillingEvent.INVOICE_FAILED;
            default -> null;
        };
        if (canonical == null) {
            return List.of();
        }
        Map<String, Object> data = verified.parsed();
        Long amountCents = minorUnits(data.get("amount"));
        Instant occurred = occurredAt(data.get("created"));
        return List.of(NormalizedBillingEvent.of(canonical, verified.providerEventId(), occurred)
            .invoice(Payloads.text(data, "id"))
            .customer(Payloads.text(data, "customer_id"))
            .amountCents(amountCents)
            .currency(data.get("currency") == null ? currency : String.valueOf(data.get("currency")))
            .hostedUrl(Payloads.text(data, "invoice_url"))
            .raw(data)
            .build());
    }

    /** {@code created} is a unix-ish stamp when it is all digits; otherwise "now". */
    private static Instant occurredAt(Object created) {
        if (created == null) {
            return Instant.now();
        }
        String digits = String.valueOf(created).replace("-", "");
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return Instant.now();
        }
        String seconds = digits.length() > 10 ? digits.substring(0, 10) : digits;
        return Instant.ofEpochSecond(Long.parseLong(seconds));
    }

    private InvoiceRef invoiceRef(Map<String, Object> data) {
        Long total = minorUnits(data.get("amount"));
        return new InvoiceRef(data.get("id") == null ? "" : String.valueOf(data.get("id")), Payloads.text(data, "external_id"),
            "PAID".equals(data.get("status")) ? "paid" : "open", total == null ? 0 : total,
            data.get("currency") == null ? currency : String.valueOf(data.get("currency")), Payloads.text(data, "invoice_url"),
            null, null, null, null, null);
    }

    private Map<String, Object> send(String method, String path, Object body) {
        String basic = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        return http.json(method, apiBase + path, body, Map.of("Authorization", "Basic " + basic), "Xendit");
    }

    /** Minor → major units without ever touching a float. */
    static BigDecimal majorUnits(long cents) {
        return BigDecimal.valueOf(cents).divide(MINOR_UNITS);
    }

    /**
     * Major units as Xendit sends them ({@code "499.99"}, {@code 499.99},
     * {@code 500}) → integer minor units, exactly. Going through a float turns
     * 0.29 into 28 (ADR 0006), so the conversion is decimal end to end.
     */
    static Long minorUnits(Object major) {
        if (major == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(major)).multiply(MINOR_UNITS)
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new BillingProviderError("Unparseable amount from Xendit: " + major);
        }
    }
}
