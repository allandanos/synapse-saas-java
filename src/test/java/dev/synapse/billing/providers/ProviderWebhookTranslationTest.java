package dev.synapse.billing.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.billing.NormalizedBillingEvent;
import dev.synapse.billing.ProviderHttp;
import dev.synapse.billing.VerifiedWebhook;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Translation fixtures: each provider's vocabulary → the canonical one, and an
 * unmapped provider event translating to nothing at all.
 */
class ProviderWebhookTranslationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ProviderHttp HTTP = new ProviderHttp(JSON);
    private static final Instant RECEIVED = Instant.parse("2026-09-01T00:00:00Z");

    private final StripeBillingProvider stripe = new StripeBillingProvider(HTTP, JSON, "sk", "whsec", "http://localhost:1/v1");
    private final PaddleBillingProvider paddle = new PaddleBillingProvider(HTTP, JSON, "k", "s", "http://localhost:1");
    private final XenditBillingProvider xendit = new XenditBillingProvider(HTTP, JSON, "k", "t", "PHP", "http://localhost:1");
    private final PayMongoBillingProvider paymongo = new PayMongoBillingProvider(HTTP, JSON, "k", "s", "PHP", "http://localhost:1/v1");
    private final ManualBillingProvider manual = new ManualBillingProvider(JSON, "t", "PHP");

    @Test
    void stripeSubscriptionUpdatedCarriesTheStatusAndPeriodEnd() {
        Map<String, Object> body = Map.of("id", "evt_1", "type", "customer.subscription.updated", "created", 1_750_000_000,
            "data", Map.of("object", Map.of("id", "sub_9", "customer", "cus_9", "status", "past_due",
                "current_period_end", 1_760_000_000, "metadata", Map.of("plan_key", "pro"))));
        NormalizedBillingEvent event = only(stripe.translateWebhook(verified("evt_1", "customer.subscription.updated", body)));
        assertThat(event.eventType()).isEqualTo(NormalizedBillingEvent.SUBSCRIPTION_UPDATED);
        assertThat(event.providerSubscriptionId()).isEqualTo("sub_9");
        assertThat(event.providerCustomerId()).isEqualTo("cus_9");
        assertThat(event.status()).isEqualTo("past_due");
        assertThat(event.planKey()).isEqualTo("pro");
        assertThat(event.currentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1_760_000_000));
        assertThat(event.occurredAt()).isEqualTo(Instant.ofEpochSecond(1_750_000_000));
    }

    @Test
    void stripeInvoicePaidCarriesTheInvoiceAmountAndHostedUrl() {
        Map<String, Object> body = Map.of("id", "evt_2", "type", "invoice.paid", "created", 1_750_000_000,
            "data", Map.of("object", Map.of("id", "in_1", "customer", "cus_9", "subscription", "sub_9", "amount_paid", 149900,
                "currency", "php", "hosted_invoice_url", "https://stripe.example/i/in_1", "status", "paid")));
        NormalizedBillingEvent event = only(stripe.translateWebhook(verified("evt_2", "invoice.paid", body)));
        assertThat(event.eventType()).isEqualTo(NormalizedBillingEvent.INVOICE_PAID);
        assertThat(event.providerInvoiceId()).isEqualTo("in_1");
        assertThat(event.providerSubscriptionId()).isEqualTo("sub_9");
        assertThat(event.amountCents()).isEqualTo(149900);
        assertThat(event.currency()).isEqualTo("PHP");
        assertThat(event.hostedUrl()).isEqualTo("https://stripe.example/i/in_1");
    }

    @Test
    void stripeIgnoresEventsItDoesNotMap() {
        assertThat(stripe.translateWebhook(verified("evt_3", "customer.created", Map.of("type", "customer.created")))).isEmpty();
    }

    @Test
    void paddleTransactionCompletedBecomesCheckoutCompleted() {
        Map<String, Object> body = Map.of("event_id", "evt_pdl", "event_type", "transaction.completed",
            "data", Map.of("id", "txn_1", "customer_id", "ctm_1", "subscription_id", "sub_1", "currency_code", "php",
                "totals", Map.of("total", 99900), "custom_data", Map.of("plan_key", "starter")));
        NormalizedBillingEvent event = only(paddle.translateWebhook(verified("evt_pdl", "transaction.completed", body)));
        assertThat(event.eventType()).isEqualTo(NormalizedBillingEvent.CHECKOUT_COMPLETED);
        assertThat(event.providerSubscriptionId()).isEqualTo("sub_1");
        assertThat(event.planKey()).isEqualTo("starter");
        assertThat(event.amountCents()).isEqualTo(99900);
        assertThat(event.currency()).isEqualTo("PHP");
    }

    @Test
    void paddleSubscriptionEventsTakeTheirIdFromTheObject() {
        Map<String, Object> body = Map.of("event_id", "e", "event_type", "subscription.past_due",
            "data", Map.of("id", "sub_42", "status", "past_due"));
        NormalizedBillingEvent event = only(paddle.translateWebhook(verified("e", "subscription.past_due", body)));
        assertThat(event.eventType()).isEqualTo(NormalizedBillingEvent.SUBSCRIPTION_PAST_DUE);
        assertThat(event.providerSubscriptionId()).isEqualTo("sub_42");
    }

    /** Xendit reports MAJOR units; the conversion is exact decimal arithmetic, never a float. */
    @Test
    void xenditPaidInvoiceConvertsMajorUnitsExactly() {
        Map<String, Object> body = Map.of("id", "inv_1", "status", "PAID", "amount", 1499.99, "currency", "PHP",
            "invoice_url", "https://xendit.example/inv_1", "customer_id", "cust_1");
        NormalizedBillingEvent event = only(xendit.translateWebhook(verified("inv_1", "PAID", body)));
        assertThat(event.eventType()).isEqualTo(NormalizedBillingEvent.INVOICE_PAID);
        assertThat(event.amountCents()).isEqualTo(149999);
        assertThat(event.providerInvoiceId()).isEqualTo("inv_1");
        assertThat(event.hostedUrl()).isEqualTo("https://xendit.example/inv_1");
    }

    @Test
    void xenditExpiredInvoiceIsAFailureAndOtherStatusesAreIgnored() {
        assertThat(only(xendit.translateWebhook(verified("inv_2", "EXPIRED", Map.of("id", "inv_2", "status", "EXPIRED")))).eventType())
            .isEqualTo(NormalizedBillingEvent.INVOICE_FAILED);
        assertThat(xendit.translateWebhook(verified("inv_3", "PENDING", Map.of("id", "inv_3", "status", "PENDING")))).isEmpty();
    }

    @Test
    void paymongoCheckoutCompletedReadsTheLineItemAmountAndMetadata() {
        Map<String, Object> body = Map.of("id", "evt_pm", "type", "checkout_session.completed",
            "data", Map.of("id", "cs_1", "attributes", Map.of(
                "line_items", List.of(Map.of("name", "Pro", "amount", 149900)),
                "metadata", Map.of("plan_key", "pro", "currency", "PHP"))));
        NormalizedBillingEvent event = only(paymongo.translateWebhook(verified("evt_pm", "checkout_session.completed", body)));
        assertThat(event.eventType()).isEqualTo(NormalizedBillingEvent.CHECKOUT_COMPLETED);
        assertThat(event.amountCents()).isEqualTo(149900);
        assertThat(event.planKey()).isEqualTo("pro");
    }

    @Test
    void paymongoPaymentFailedIsItsOwnCanonicalEvent() {
        Map<String, Object> body = Map.of("id", "evt_pm2", "type", "payment.failed",
            "data", Map.of("attributes", Map.of("amount", 500, "metadata", Map.of())));
        assertThat(only(paymongo.translateWebhook(verified("evt_pm2", "payment.failed", body))).eventType())
            .isEqualTo(NormalizedBillingEvent.PAYMENT_FAILED);
    }

    @Test
    void manualEventsMapOnlyTheThreeTheProviderEmits() {
        Map<String, Object> activated = Map.of("id", "m1", "type", "manual.subscription.activated",
            "data", Map.of("subscription_id", "manualsub_1", "customer_id", "manual_1", "plan_key", "pro"));
        NormalizedBillingEvent event = only(manual.translateWebhook(verified("m1", "manual.subscription.activated", activated)));
        assertThat(event.eventType()).isEqualTo(NormalizedBillingEvent.SUBSCRIPTION_ACTIVATED);
        assertThat(event.planKey()).isEqualTo("pro");
        assertThat(manual.translateWebhook(verified("m2", "manual.whatever", Map.of("type", "manual.whatever")))).isEmpty();
    }

    private static VerifiedWebhook verified(String id, String type, Map<String, Object> body) {
        return new VerifiedWebhook(id, type, body, RECEIVED);
    }

    private static NormalizedBillingEvent only(List<NormalizedBillingEvent> events) {
        assertThat(events).hasSize(1);
        return events.get(0);
    }
}
