package dev.synapse.billing.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.billing.BillingRefs.ChangePlanRequest;
import dev.synapse.billing.BillingRefs.CheckoutResult;
import dev.synapse.billing.BillingRefs.CreateCheckoutRequest;
import dev.synapse.billing.BillingRefs.CreateCustomerRequest;
import dev.synapse.billing.BillingRefs.InvoiceRef;
import dev.synapse.billing.BillingRefs.SubscriptionRef;
import dev.synapse.billing.ProviderHttp;
import dev.synapse.core.errors.BillingProviderError;
import dev.synapse.support.StubProviderServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The provider HTTP clients against a local stub: request shape in, mapped refs out. */
class ProviderHttpClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ProviderHttp HTTP = new ProviderHttp(JSON);
    private static final UUID ORG = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void stripeCreatesAFormEncodedCheckoutSessionWithBasicAuth() throws IOException {
        try (StubProviderServer stub = new StubProviderServer()) {
            stub.on("POST", "/v1/checkout/sessions", "{\"id\":\"cs_1\",\"url\":\"https://stripe.example/pay/cs_1\"}");
            StripeBillingProvider stripe = new StripeBillingProvider(HTTP, JSON, "sk_test_123", "whsec", stub.baseUrl() + "/v1");

            CheckoutResult result = stripe.createCheckout(new CreateCheckoutRequest("pro", "Pro", 149900, "PHP", "month",
                "cus_1", "https://app.example/ok", "https://app.example/cancel", ORG));

            assertThat(result.url()).isEqualTo("https://stripe.example/pay/cs_1");
            assertThat(result.providerCheckoutId()).isEqualTo("cs_1");
            StubProviderServer.Call call = stub.lastCall();
            assertThat(call.headers().get("content-type")).isEqualTo("application/x-www-form-urlencoded");
            assertThat(call.headers().get("authorization"))
                .isEqualTo("Basic " + Base64.getEncoder().encodeToString("sk_test_123:".getBytes(StandardCharsets.UTF_8)));
            // Money stays in minor units and the interval rides the nested price_data
            assertThat(call.body()).contains("unit_amount%5D=149900").contains("recurring%5D%5Binterval%5D=month")
                .contains("currency%5D=php").contains("mode=subscription").contains("customer=cus_1");
        }
    }

    @Test
    void stripeChangePlanReadsTheCurrentItemFirst() throws IOException {
        try (StubProviderServer stub = new StubProviderServer()) {
            stub.on("GET", "/v1/subscriptions/sub_1", "{\"id\":\"sub_1\",\"status\":\"active\",\"items\":{\"data\":[{\"id\":\"si_9\"}]}}");
            stub.on("POST", "/v1/subscriptions/sub_1",
                "{\"id\":\"sub_1\",\"status\":\"active\",\"customer\":\"cus_1\",\"current_period_end\":1760000000}");
            StripeBillingProvider stripe = new StripeBillingProvider(HTTP, JSON, "sk", "whsec", stub.baseUrl() + "/v1");

            SubscriptionRef ref = stripe.changePlan("sub_1", new ChangePlanRequest("starter", 49900, "PHP", "month"));

            assertThat(ref.providerSubscriptionId()).isEqualTo("sub_1");
            assertThat(ref.currentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1_760_000_000));
            assertThat(stub.calls()).hasSize(2);
            assertThat(stub.calls().get(0).method()).isEqualTo("GET");
            assertThat(stub.lastCall().body()).contains("items%5B0%5D%5Bid%5D=si_9").contains("unit_amount%5D=49900");
        }
    }

    @Test
    void stripeMapsInvoiceListEntriesOntoInvoiceRefs() throws IOException {
        try (StubProviderServer stub = new StubProviderServer()) {
            stub.on("GET", "/v1/invoices", """
                {"data":[{"id":"in_1","number":"A-1","status":"paid","total":149900,"currency":"php",
                          "hosted_invoice_url":"https://h","invoice_pdf":"https://p","created":1750000000,
                          "status_transitions":{"paid_at":1750000500}}]}
                """);
            StripeBillingProvider stripe = new StripeBillingProvider(HTTP, JSON, "sk", "whsec", stub.baseUrl() + "/v1");

            List<InvoiceRef> invoices = stripe.listInvoices("cus_1", 5);

            assertThat(invoices).hasSize(1);
            InvoiceRef invoice = invoices.get(0);
            assertThat(invoice.totalCents()).isEqualTo(149900);
            assertThat(invoice.currency()).isEqualTo("PHP");
            assertThat(invoice.paidAt()).isEqualTo(Instant.ofEpochSecond(1_750_000_500));
        }
    }

    @Test
    void stripeSurfacesItsErrorMessageAsA502() throws IOException {
        try (StubProviderServer stub = new StubProviderServer()) {
            stub.failWith(402, "{\"error\":{\"message\":\"Your card was declined.\"}}");
            StripeBillingProvider stripe = new StripeBillingProvider(HTTP, JSON, "sk", "whsec", stub.baseUrl() + "/v1");

            assertThatThrownBy(() -> stripe.createCustomer(new CreateCustomerRequest("a@example.com", "A", ORG, "PHP")))
                .isInstanceOf(BillingProviderError.class)
                .hasMessageContaining("Stripe API error: Your card was declined.")
                .satisfies(thrown -> assertThat(((BillingProviderError) thrown).status()).isEqualTo(502));
        }
    }

    /** Xendit takes MAJOR units on the wire; the conversion must stay exact. */
    @Test
    void xenditSendsMajorUnitsAndBasicAuth() throws IOException {
        try (StubProviderServer stub = new StubProviderServer()) {
            stub.on("POST", "/invoices", "{\"id\":\"inv_1\",\"invoice_url\":\"https://xendit.example/inv_1\"}");
            XenditBillingProvider xendit = new XenditBillingProvider(HTTP, JSON, "xnd_key", "token", "PHP", stub.baseUrl());

            CheckoutResult result = xendit.createCheckout(new CreateCheckoutRequest("pro", "Pro", 149999, "PHP", "month",
                "cust_1", "https://ok", "https://cancel", ORG));

            assertThat(result.url()).isEqualTo("https://xendit.example/inv_1");
            assertThat(stub.lastCall().headers().get("content-type")).isEqualTo("application/json");
            assertThat(stub.lastCall().body()).contains("\"amount\":1499.99").contains("\"currency\":\"PHP\"");
        }
    }

    @Test
    void paymongoSendsMinorUnitsInsideItsDataEnvelope() throws IOException {
        try (StubProviderServer stub = new StubProviderServer()) {
            stub.on("POST", "/v1/checkout_sessions",
                "{\"data\":{\"id\":\"cs_pm\",\"attributes\":{\"checkout_url\":\"https://paymongo.example/cs_pm\"}}}");
            PayMongoBillingProvider paymongo = new PayMongoBillingProvider(HTTP, JSON, "sk", "whsk", "PHP", stub.baseUrl() + "/v1");

            CheckoutResult result = paymongo.createCheckout(new CreateCheckoutRequest("pro", "Pro", 149900, "PHP", "month",
                null, null, null, ORG));

            assertThat(result.url()).isEqualTo("https://paymongo.example/cs_pm");
            assertThat(result.providerCheckoutId()).isEqualTo("cs_pm");
            assertThat(stub.lastCall().body()).contains("\"amount\":149900").contains("\"plan_key\":\"pro\"");
        }
    }

    @Test
    void paddlePostsATransactionAndReadsTheCheckoutUrlOutOfData() throws IOException {
        try (StubProviderServer stub = new StubProviderServer()) {
            stub.on("POST", "/transactions",
                "{\"data\":{\"id\":\"txn_1\",\"checkout\":{\"url\":\"https://paddle.example/txn_1\"}}}");
            PaddleBillingProvider paddle = new PaddleBillingProvider(HTTP, JSON, "pdl_key", "pdl_secret", stub.baseUrl());

            CheckoutResult result = paddle.createCheckout(new CreateCheckoutRequest("pro", "Pro", 149900, "PHP", "month",
                null, null, null, ORG));

            assertThat(result.url()).isEqualTo("https://paddle.example/txn_1");
            assertThat(stub.lastCall().headers().get("authorization")).isEqualTo("Bearer pdl_key");
            assertThat(stub.lastCall().body()).contains("\"unit_amount\":149900").contains("\"plan_key\":\"pro\"");
        }
    }
}
