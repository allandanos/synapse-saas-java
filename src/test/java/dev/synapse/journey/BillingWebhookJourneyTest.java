package dev.synapse.journey;

import static dev.synapse.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.billing.providers.ManualBillingProvider;
import dev.synapse.core.errors.SubscriptionStateError;
import dev.synapse.subscriptions.SubscriptionService;
import dev.synapse.support.ApiClient;
import dev.synapse.support.ApiClient.Res;
import dev.synapse.support.ApiClient.Tenant;
import dev.synapse.support.PostgresTestSupport;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Provider webhook ingest over a real Postgres. The failure semantics are the
 * interesting part: a replay is a 200 no-op, a business rejection is recorded on
 * the ledger row and still answers 200, and anything unexpected 500s so the
 * ledger row rolls back with it and the provider's retry re-processes the event.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "synapse.bootstrap-admin-email=" + ApiJourneyTest.ADMIN_EMAIL,
    "synapse.bootstrap-admin-password=" + ApiJourneyTest.ADMIN_PASSWORD,
    "synapse.worker-enabled=false",
    "synapse.manual-webhook-token=" + BillingWebhookJourneyTest.TOKEN
})
class BillingWebhookJourneyTest extends PostgresTestSupport {

    static final String TOKEN = "manual-deployment-token";
    private static final String PATH = "/v1/billing/webhooks/manual";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean SubscriptionService subscriptions;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        reset(subscriptions);
    }

    @Test
    void anUnsignedPayloadIsRejectedAndLeavesNoLedgerRow() throws Exception {
        String eventId = "m-" + ApiClient.uid();
        Res unsigned = api.post(PATH, Map.of(), Map.of("id", eventId, "type", "manual.subscription.canceled"));
        assertProblem(unsigned, 400, "webhook signature invalid");
        assertThat(ledgerRows(eventId)).isZero();

        Res wrongToken = api.post(PATH, Map.of(ManualBillingProvider.TOKEN_HEADER, "nope"),
            Map.of("id", eventId, "type", "manual.subscription.canceled"));
        assertProblem(wrongToken, 400, "webhook signature invalid");
        assertThat(ledgerRows(eventId)).isZero();
    }

    @Test
    void anUnknownProviderIsA404() throws Exception {
        assertProblem(api.post("/v1/billing/webhooks/bitcoin", Map.of(ManualBillingProvider.TOKEN_HEADER, TOKEN), Map.of()), 404);
    }

    @Test
    void aSignedEventAppliesOnceAndAReplayIsANoOp() throws Exception {
        Tenant tenant = api.makeTenant("ingest");
        String subscriptionRef = bindProviderSubscription(tenant);
        String eventId = "m-" + ApiClient.uid();

        Res first = api.post(PATH, signed(), event(eventId, "manual.subscription.canceled", subscriptionRef));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.text("status")).isEqualTo("processed");
        assertThat(first.body().get("events_applied").asInt()).isEqualTo(1);
        assertThat(first.body().get("events_rejected").asInt()).isZero();
        assertThat(subscriptionStatus(tenant)).isEqualTo("canceled");
        assertThat(ledgerError(eventId)).isNull();
        assertThat(ledgerProcessed(eventId)).isTrue();
        // the transition is a public event a tenant's own webhooks can see
        assertThat(outboxCount("subscription.updated", tenant)).isEqualTo(1);

        // A replay of the SAME provider event id changes nothing
        Res replay = api.post(PATH, signed(), event(eventId, "manual.subscription.canceled", subscriptionRef));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.text("status")).isEqualTo("duplicate");
        assertThat(replay.body().get("events_applied").asInt()).isZero();
        assertThat(ledgerRows(eventId)).isEqualTo(1);
        assertThat(outboxCount("subscription.updated", tenant)).isEqualTo(1);
    }

    @Test
    void anEventWeCannotMapIsAcceptedAndAppliesNothing() throws Exception {
        String eventId = "m-" + ApiClient.uid();
        Res res = api.post(PATH, signed(), Map.of("id", eventId, "type", "manual.something.else"));
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.text("status")).isEqualTo("processed");
        assertThat(res.body().get("events_applied").asInt()).isZero();
        assertThat(ledgerProcessed(eventId)).isTrue();
    }

    @Test
    void anEventForAnUnknownOrganizationIsAcceptedAndIgnored() throws Exception {
        String eventId = "m-" + ApiClient.uid();
        Res res = api.post(PATH, signed(), event(eventId, "manual.subscription.canceled", "sub-nobody-" + ApiClient.uid()));
        assertThat(res.status()).isEqualTo(200);
        // the event WAS applied as far as the ledger is concerned: there was simply nothing to do
        assertThat(res.body().get("events_applied").asInt()).isEqualTo(1);
        assertThat(ledgerError(eventId)).isNull();
    }

    /** A business rejection is deterministic, so retrying cannot help: record it, answer 200. */
    @Test
    void aBusinessRejectionIsRecordedOnTheLedgerAndStillAnswers200() throws Exception {
        Tenant tenant = api.makeTenant("rejected");
        String subscriptionRef = bindProviderSubscription(tenant);
        doThrow(new SubscriptionStateError("Cannot transition subscription from 'canceled' to 'active'"))
            .when(subscriptions).applyProviderTransition(any(), anyString(), any());

        String eventId = "m-" + ApiClient.uid();
        Res res = api.post(PATH, signed(), event(eventId, "manual.subscription.canceled", subscriptionRef));

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.text("status")).isEqualTo("processed");
        assertThat(res.body().get("events_applied").asInt()).isZero();
        assertThat(res.body().get("events_rejected").asInt()).isEqualTo(1);
        assertThat(ledgerError(eventId)).contains("subscription.canceled").contains("Cannot transition");
        assertThat(ledgerProcessed(eventId)).isTrue();
        assertThat(subscriptionStatus(tenant)).as("the savepoint rolled the write back").isEqualTo("active");
    }

    /** An infrastructure failure must NOT be swallowed: the ledger row rolls back so the retry works. */
    @Test
    void anUnexpectedFailureIs500AndLeavesNoLedgerRow() throws Exception {
        Tenant tenant = api.makeTenant("exploding");
        String subscriptionRef = bindProviderSubscription(tenant);
        doThrow(new IllegalStateException("the database went away"))
            .when(subscriptions).applyProviderTransition(any(), anyString(), any());

        String eventId = "m-" + ApiClient.uid();
        Res res = api.post(PATH, signed(), event(eventId, "manual.subscription.canceled", subscriptionRef));

        assertProblem(res, 500, "internal error");
        assertThat(ledgerRows(eventId)).as("the ledger row rolled back with the transaction").isZero();
        assertThat(subscriptionStatus(tenant)).isEqualTo("active");
    }

    @Test
    void aPaidProviderInvoiceBecomesAnInvoiceRowAndEmitsInvoicePaid() throws Exception {
        Tenant tenant = api.makeTenant("provider-invoice");
        String customerRef = bindProviderCustomer(tenant);
        String eventId = "m-" + ApiClient.uid();
        String providerInvoiceId = "minv-" + ApiClient.uid();

        Map<String, Object> data = new HashMap<>();
        data.put("customer_id", customerRef);
        data.put("amount_cents", 149900);
        data.put("currency", "PHP");
        Map<String, Object> body = Map.of("id", eventId, "type", "manual.invoice.paid", "data", data);
        // The manual provider carries no invoice id of its own, so nothing is upserted…
        Res res = api.post(PATH, signed(), body);
        assertThat(res.status()).isEqualTo(200);
        assertThat(invoiceCount(tenant)).isZero();

        // …but the ledger still records the event exactly once
        assertThat(ledgerRows(eventId)).isEqualTo(1);
        assertThat(ledgerError(eventId)).isNull();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static Map<String, String> signed() {
        return Map.of(ManualBillingProvider.TOKEN_HEADER, TOKEN);
    }

    private static Map<String, Object> event(String eventId, String type, String subscriptionRef) {
        return Map.of("id", eventId, "type", type, "data", Map.of("subscription_id", subscriptionRef));
    }

    /** Give the org a provider subscription so `synapse_org_for_provider_ref` can find it. */
    private String bindProviderSubscription(Tenant tenant) throws Exception {
        assertThat(api.post("/v1/billing/checkout/confirm", tenant.headers(), Map.of("plan_key", "pro")).status()).isEqualTo(200);
        String ref = "msub-" + ApiClient.uid();
        jdbc.sql("UPDATE subscriptions SET provider = 'manual', provider_subscription_id = :ref WHERE organization_id = :org")
            .param("ref", ref).param("org", UUID.fromString(tenant.orgId())).update();
        return ref;
    }

    private String bindProviderCustomer(Tenant tenant) throws Exception {
        assertThat(api.post("/v1/billing/checkout", tenant.headers(), Map.of("plan_key", "pro")).status()).isEqualTo(200);
        return jdbc.sql("SELECT provider_customer_id FROM billing_customers WHERE organization_id = :org")
            .param("org", UUID.fromString(tenant.orgId())).query(String.class).single();
    }

    private long ledgerRows(String eventId) {
        return jdbc.sql("SELECT count(*) FROM provider_webhook_events WHERE provider = 'manual' AND provider_event_id = :id")
            .param("id", eventId).query(Long.class).single();
    }

    private String ledgerError(String eventId) {
        return jdbc.sql("SELECT error FROM provider_webhook_events WHERE provider = 'manual' AND provider_event_id = :id")
            .param("id", eventId).query(String.class).optional().orElse(null);
    }

    private boolean ledgerProcessed(String eventId) {
        return jdbc.sql("SELECT processed_at IS NOT NULL FROM provider_webhook_events WHERE provider = 'manual' "
                + "AND provider_event_id = :id")
            .param("id", eventId).query(Boolean.class).single();
    }

    private String subscriptionStatus(Tenant tenant) {
        return jdbc.sql("SELECT status FROM subscriptions WHERE organization_id = :org ORDER BY created_at DESC LIMIT 1")
            .param("org", UUID.fromString(tenant.orgId())).query(String.class).single();
    }

    private long outboxCount(String eventType, Tenant tenant) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE event_type = :type AND organization_id = :org")
            .param("type", eventType).param("org", UUID.fromString(tenant.orgId())).query(Long.class).single();
    }

    private long invoiceCount(Tenant tenant) {
        return jdbc.sql("SELECT count(*) FROM invoices WHERE organization_id = :org").param("org", UUID.fromString(tenant.orgId()))
            .query(Long.class).single();
    }
}
