package dev.synapse.journey;

import static dev.synapse.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.billing.invoicing.InvoicingService;
import dev.synapse.subscriptions.Subscription;
import dev.synapse.subscriptions.SubscriptionRepository;
import dev.synapse.support.ApiClient;
import dev.synapse.support.ApiClient.Res;
import dev.synapse.support.ApiClient.Tenant;
import dev.synapse.support.PostgresTestSupport;
import dev.synapse.worker.JobRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Milestone-4 money journeys over a real Postgres: manual checkout → confirm →
 * draft → finalize → PDF → operator pay/void, overage lines priced from real
 * usage, a mid-period plan change whose proration lands on the next draft, and
 * the recurring-billing job renewing an ended period.
 *
 * <p>The scheduler is off here so every job runs exactly when the test asks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "synapse.bootstrap-admin-email=" + ApiJourneyTest.ADMIN_EMAIL,
    "synapse.bootstrap-admin-password=" + ApiJourneyTest.ADMIN_PASSWORD,
    "synapse.worker-enabled=false",
    "synapse.manual-pay-to-instructions=Bank transfer to Synapse Inc, account 0000-1111-2222"
})
class InvoicingJourneyTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired JobRegistry jobs;
    @Autowired SubscriptionRepository subscriptions;

    ApiClient api;
    Map<String, String> platform;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, json);
        Res login = api.post("/v1/auth/login", Map.of(),
            Map.of("email", ApiJourneyTest.ADMIN_EMAIL, "password", ApiJourneyTest.ADMIN_PASSWORD));
        assertThat(login.status()).isEqualTo(200);
        platform = Map.of("Authorization", "Bearer " + login.body().at("/tokens/access_token").asText());
    }

    @Test
    void manualCheckoutConfirmsThenTheInvoiceGoesDraftOpenPaid() throws Exception {
        Tenant tenant = api.makeTenant("invoice");

        // 1 — manual checkout has no URL, only instructions the tenant can act on
        Res checkout = api.post("/v1/billing/checkout", tenant.headers(), Map.of("plan_key", "pro"));
        assertThat(checkout.status()).isEqualTo(200);
        assertThat(checkout.body().get("url").isNull()).isTrue();
        assertThat(checkout.text("provider")).isEqualTo("manual");
        assertThat(checkout.text("manual_instructions")).contains("Pro").contains("month");
        // ensure_customer created exactly one billing customer for the org
        assertThat(countCustomers(tenant)).isEqualTo(1);

        // 2 — the tenant confirms (manual has no payment truth of its own)
        Res confirm = api.post("/v1/billing/checkout/confirm", tenant.headers(), Map.of("plan_key", "pro"));
        assertThat(confirm.status()).isEqualTo(200);
        assertThat(confirm.text("plan_key")).isEqualTo("pro");
        assertThat(api.get("/v1/subscription", tenant.headers()).body().at("/subscription/plan/key").asText()).isEqualTo("pro");
        assertThat(countCustomers(tenant)).as("confirm reuses the customer").isEqualTo(1);
        // The settled charge is recorded as an OPEN provider invoice with no lines:
        // a provider's invoice shape is the provider's, ours is ours.
        List<Map<String, Object>> providerInvoices = providerInvoicesOf(UUID.fromString(tenant.orgId()));
        assertThat(providerInvoices).hasSize(1);
        assertThat(providerInvoices.get(0).get("status")).isEqualTo("open");
        assertThat(providerInvoices.get(0).get("number")).isEqualTo("null");

        // 3 — draft: the plan line comes from the purchase-time snapshot
        Res draft = api.post("/v1/billing/invoices/draft", tenant.headers(), Map.of());
        assertThat(draft.status()).isEqualTo(201);
        assertThat(draft.text("status")).isEqualTo("draft");
        assertThat(draft.body().get("number").isNull()).isTrue();
        JsonNode lines = draft.body().get("lines");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("kind").asText()).isEqualTo("plan");
        assertThat(lines.get(0).get("description").asText()).isEqualTo("Pro plan (monthly)");
        long total = draft.body().get("total_cents").asLong();
        assertThat(total).isEqualTo(lines.get(0).get("amount_cents").asLong()).isPositive();
        String invoiceId = draft.text("id");

        // drafting twice for the same period returns the same invoice
        assertThat(api.post("/v1/billing/invoices/draft", tenant.headers(), Map.of()).text("id")).isEqualTo(invoiceId);

        // 4 — finalize: a number, an issue date, status open, and both outbox rows
        Res finalized = api.post("/v1/billing/invoices/" + invoiceId + "/finalize", tenant.headers(), null);
        assertThat(finalized.status()).isEqualTo(200);
        assertThat(finalized.text("status")).isEqualTo("open");
        assertThat(finalized.text("number")).matches("INV-\\d{6}-0001");
        assertThat(finalized.body().get("issued_at").isNull()).isFalse();
        assertThat(countOutbox("invoice.created", invoiceId)).isEqualTo(1);
        assertThat(countOutbox("invoice.email", invoiceId)).isEqualTo(1);
        assertThat(audience("invoice.email")).isEqualTo("internal");

        // Re-finalizing is a no-op: the same number, the same issue date, nothing emitted twice
        Res again = api.post("/v1/billing/invoices/" + invoiceId + "/finalize", tenant.headers(), null);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.text("status")).isEqualTo("open");
        assertThat(again.text("number")).isEqualTo(finalized.text("number"));
        assertThat(again.text("issued_at")).isEqualTo(finalized.text("issued_at"));
        assertThat(countOutbox("invoice.created", invoiceId)).isEqualTo(1);
        assertThat(countOutbox("invoice.email", invoiceId)).isEqualTo(1);

        // 5 — the PDF renders with the pay-to instructions
        MockHttpServletResponse pdf = api.raw(HttpMethod.GET, "/v1/billing/invoices/" + invoiceId + "/pdf", tenant.headers());
        assertThat(pdf.getStatus()).isEqualTo(200);
        assertThat(pdf.getContentType()).startsWith("application/pdf");
        assertThat(pdf.getHeader("Content-Disposition")).contains("attachment").contains(finalized.text("number"));
        assertThat(pdf.getContentAsByteArray()).hasSizeGreaterThan(500);
        assertThat(new String(pdf.getContentAsByteArray(), 0, 4)).isEqualTo("%PDF");

        // 6 — money movements are operator-only: the tenant cannot see the route at all
        assertProblem(api.post("/v1/billing/admin/invoices/" + invoiceId + "/pay", tenant.headers(), Map.of("amount_cents", 1)), 404);
        // a partial payment is refused with the expected/received amounts
        assertProblem(api.post("/v1/billing/admin/invoices/" + invoiceId + "/pay", platform, Map.of("amount_cents", total - 1)),
            422, "validation failed");
        Res paid = api.post("/v1/billing/admin/invoices/" + invoiceId + "/pay", platform,
            Map.of("amount_cents", total, "reference", "bank-transfer-1"));
        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.text("status")).isEqualTo("paid");
        assertThat(paid.body().get("paid_at").isNull()).isFalse();
        assertThat(countOutbox("invoice.paid", invoiceId)).isEqualTo(1);

        // 7 — paid is terminal
        assertProblem(api.post("/v1/billing/admin/invoices/" + invoiceId + "/void", platform, null), 422, "validation failed");

        // 8 — the reports see both: ours paid, the provider's charge still open
        Res spend = api.get("/v1/billing/spend-summary", tenant.headers());
        assertThat(spend.body().get("paid_cents").asLong()).isEqualTo(total);
        assertThat(spend.body().get("outstanding_cents").asLong()).isEqualTo(total); // the recorded checkout charge
        assertThat(spend.body().get("billed_cents").asLong()).isEqualTo(2 * total);
        assertThat(api.get("/v1/billing/spend-monthly", tenant.headers()).body().get(0).get("total_cents").asLong()).isEqualTo(total);
        Res revenue = api.get("/v1/billing/admin/revenue-summary", platform);
        assertThat(revenue.body().get("collected_cents").asLong()).isGreaterThanOrEqualTo(total);
        assertThat(revenue.body().get("paying_organizations").asLong()).isPositive();
    }

    @Test
    void aDraftPricesOverageFromRealUsageAndTheLinesReconcile() throws Exception {
        Tenant tenant = api.makeTenant("overage");
        assertThat(api.post("/v1/billing/checkout/confirm", tenant.headers(), Map.of("plan_key", "pro")).status()).isEqualTo(200);

        // ai_tokens is the metric the catalog prices past its cap (20c per 1,000 units);
        // api_requests is enforced but never billed, so it can never make a line.
        long included = api.get("/v1/entitlements", tenant.headers()).body().at("/limits/ai_tokens/value").asLong();
        long overBy = 2_500;
        jdbc.sql("""
                INSERT INTO usage_counters (organization_id, metric, period_start, quantity_total, last_event_at)
                VALUES (:org, 'ai_tokens', date_trunc('month', now())::date, :total, now())
                ON CONFLICT (organization_id, metric, period_start)
                DO UPDATE SET quantity_total = EXCLUDED.quantity_total
                """)
            .param("org", UUID.fromString(tenant.orgId())).param("total", included + overBy).update();

        Res draft = api.post("/v1/billing/invoices/draft", tenant.headers(), Map.of());
        assertThat(draft.status()).isEqualTo(201);
        JsonNode overage = lineOfKind(draft.body(), "overage");
        assertThat(overage).as("a metric with a price and usage past its cap bills: %s", draft.body()).isNotNull();
        assertThat(overage.get("metric").asText()).isEqualTo("ai_tokens");
        assertThat(overage.get("description").asText()).contains("ai_tokens overage").contains("units over plan").contains("per 1,000");
        // 2,500 units over a 1,000-unit block rounds up to 3 blocks
        assertThat(overage.get("quantity").asLong()).isEqualTo(3);
        assertThat(overage.get("unit_amount_cents").asLong()).isEqualTo(20);
        assertThat(overage.get("amount_cents").asLong()).isEqualTo(60);
        // qty × unit price == amount, always
        assertThat(overage.get("amount_cents").asLong())
            .isEqualTo(overage.get("quantity").asLong() * overage.get("unit_amount_cents").asLong());
        // and the invoice total is the sum of its lines
        long sum = 0;
        for (JsonNode line : draft.body().get("lines")) {
            sum += line.get("amount_cents").asLong();
        }
        assertThat(draft.body().get("total_cents").asLong()).isEqualTo(sum);
        assertThat(draft.body().get("subtotal_cents").asLong()).isEqualTo(sum);
    }

    /**
     * Billed in arrears at the NEW price, so an UPGRADE mid-period is owed a
     * credit (the elapsed half was only worth the old plan) and a downgrade owes
     * a charge. Either way the correction rides the next draft exactly once.
     */
    @Test
    void anUpgradeMidPeriodCreditsTheNextDraft() throws Exception {
        Tenant tenant = api.makeTenant("upgrade");
        assertThat(api.post("/v1/billing/checkout/confirm", tenant.headers(), Map.of("plan_key", "starter")).status()).isEqualTo(200);
        backdateHalfAPeriod(tenant);

        assertThat(api.post("/v1/subscription/change", tenant.headers(), Map.of("plan_key", "pro")).status()).isEqualTo(200);
        assertThat(pendingAdjustments(tenant)).hasSize(1);

        Res draft = api.post("/v1/billing/invoices/draft", tenant.headers(), Map.of());
        assertThat(draft.status()).isEqualTo(201);
        JsonNode credit = lineOfKind(draft.body(), "credit");
        assertThat(credit).as("upgrade ⇒ credit line: %s", draft.body()).isNotNull();
        assertThat(credit.get("amount_cents").asLong()).isNegative();
        assertThat(credit.get("quantity").asLong()).isEqualTo(1);
        assertThat(credit.get("amount_cents").asLong()).isEqualTo(credit.get("unit_amount_cents").asLong());
        assertThat(credit.get("description").asText()).contains("Plan change starter").contains("pro").contains("credit");
        // the plan line is the NEW plan's price, and the credit nets against it
        long planCents = lineOfKind(draft.body(), "plan").get("amount_cents").asLong();
        assertThat(draft.body().get("total_cents").asLong()).isEqualTo(planCents + credit.get("amount_cents").asLong());

        // the adjustment is drained once drafted, so it cannot be billed twice
        assertThat(pendingAdjustments(tenant)).isEmpty();
        assertThat(api.post("/v1/billing/invoices/draft", tenant.headers(), Map.of()).text("id")).isEqualTo(draft.text("id"));
    }

    @Test
    void aDowngradeMidPeriodChargesTheNextDraft() throws Exception {
        Tenant tenant = api.makeTenant("downgrade");
        assertThat(api.post("/v1/billing/checkout/confirm", tenant.headers(), Map.of("plan_key", "pro")).status()).isEqualTo(200);
        backdateHalfAPeriod(tenant);

        assertThat(api.post("/v1/subscription/change", tenant.headers(), Map.of("plan_key", "starter")).status()).isEqualTo(200);

        Res draft = api.post("/v1/billing/invoices/draft", tenant.headers(), Map.of());
        JsonNode charge = lineOfKind(draft.body(), "custom");
        assertThat(charge).as("downgrade ⇒ charge line: %s", draft.body()).isNotNull();
        assertThat(charge.get("amount_cents").asLong()).isPositive();
        assertThat(charge.get("description").asText()).contains("Plan change pro").contains("starter").contains("charge");
    }

    @Test
    void theRecurringBillingJobInvoicesTheEndedPeriodAndRollsItForward() throws Exception {
        Tenant tenant = api.makeTenant("renewal");
        assertThat(api.post("/v1/billing/checkout/confirm", tenant.headers(), Map.of("plan_key", "pro")).status()).isEqualTo(200);
        UUID orgId = UUID.fromString(tenant.orgId());

        // End the period in the past so the job picks the subscription up
        jdbc.sql("UPDATE subscriptions SET current_period_start = date_trunc('month', now()), "
                + "current_period_end = now() - interval '1 minute' WHERE organization_id = :org")
            .param("org", orgId).update();
        Instant endedAt = currentSubscription(orgId).currentPeriodEnd();

        assertThat(jobs.run("advance_recurring_billing")).isGreaterThanOrEqualTo(1);

        // The ended period was billed through the invoicing engine: numbered, open
        List<Map<String, Object>> invoices = invoicesOf(orgId);
        assertThat(invoices).hasSize(1);
        assertThat(invoices.get(0).get("status")).isEqualTo("open");
        assertThat(String.valueOf(invoices.get(0).get("number"))).matches("INV-\\d{6}-0001");
        assertThat(countOutbox("invoice.email", String.valueOf(invoices.get(0).get("id")))).isEqualTo(1);

        // …and the period rolled forward from where it ended
        Subscription rolled = currentSubscription(orgId);
        assertThat(rolled.currentPeriodStart()).isEqualTo(endedAt);
        assertThat(rolled.currentPeriodEnd()).isEqualTo(endedAt.plus(30, ChronoUnit.DAYS));

        // A second run finds nothing due
        assertThat(jobs.run("advance_recurring_billing")).isZero();
    }

    @Test
    void theRenewalJobLeavesHostedProvidersAndCancelledSubscriptionsAlone() throws Exception {
        Tenant hosted = api.makeTenant("hosted-renewal");
        assertThat(api.post("/v1/billing/checkout/confirm", hosted.headers(), Map.of("plan_key", "pro")).status()).isEqualTo(200);
        jdbc.sql("UPDATE subscriptions SET provider = 'stripe', provider_subscription_id = :ref, "
                + "current_period_end = now() - interval '1 minute' WHERE organization_id = :org")
            .param("ref", "sub_" + ApiClient.uid()).param("org", UUID.fromString(hosted.orgId())).update();

        Tenant leaving = api.makeTenant("leaving");
        assertThat(api.post("/v1/billing/checkout/confirm", leaving.headers(), Map.of("plan_key", "pro")).status()).isEqualTo(200);
        assertThat(api.post("/v1/subscription/cancel", leaving.headers(), Map.of("at_period_end", true)).status()).isEqualTo(200);
        jdbc.sql("UPDATE subscriptions SET current_period_end = now() - interval '1 minute' WHERE organization_id = :org")
            .param("org", UUID.fromString(leaving.orgId())).update();

        jobs.run("advance_recurring_billing");

        assertThat(invoicesOf(UUID.fromString(hosted.orgId()))).as("Stripe renews on its side and reports by webhook").isEmpty();
        assertThat(invoicesOf(UUID.fromString(leaving.orgId()))).as("cancel_at_period_end means do not renew").isEmpty();
    }

    @Test
    void aFreePlanOrgCannotDraftAnythingBillableAndVoidingADraftIsAllowed() throws Exception {
        Tenant tenant = api.makeTenant("free-draft");
        // The default subscription is free: a draft exists but carries no lines
        Res draft = api.post("/v1/billing/invoices/draft", tenant.headers(), Map.of());
        assertThat(draft.status()).isEqualTo(201);
        assertThat(draft.body().get("lines")).isEmpty();
        assertThat(draft.body().get("total_cents").asLong()).isZero();

        Res voided = api.post("/v1/billing/admin/invoices/" + draft.text("id") + "/void", platform, null);
        assertThat(voided.status()).isEqualTo(200);
        assertThat(voided.text("status")).isEqualTo("void");
        // …and a zero-amount payment never validates
        assertProblem(api.post("/v1/billing/admin/invoices/" + draft.text("id") + "/pay", platform, Map.of("amount_cents", 0)),
            422, "validation failed");
    }

    @Test
    void invoicesAreInvisibleAcrossTenants() throws Exception {
        Tenant owner = api.makeTenant("owner-inv");
        Tenant other = api.makeTenant("other-inv");
        String invoiceId = api.post("/v1/billing/invoices/draft", owner.headers(), Map.of()).text("id");

        assertProblem(api.get("/v1/billing/invoices/" + invoiceId, other.headers()), 404, "invoice not found");
        assertThat(api.raw(HttpMethod.GET, "/v1/billing/invoices/" + invoiceId + "/pdf", other.headers()).getStatus()).isEqualTo(404);
        assertProblem(api.post("/v1/billing/invoices/" + invoiceId + "/finalize", other.headers(), null), 404, "invoice not found");
        assertThat(api.get("/v1/billing/invoices", other.headers()).body()).isEmpty();
    }

    @Test
    void theListRouteIsPagedAndExposesTheTotalCount() throws Exception {
        Tenant tenant = api.makeTenant("paged");
        api.post("/v1/billing/invoices/draft", tenant.headers(), Map.of());
        api.post("/v1/billing/invoices/draft", tenant.headers(), Map.of("period", lastMonth()));

        Res page = api.get("/v1/billing/invoices?limit=1", tenant.headers());
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).hasSize(1);
        assertThat(page.header("X-Total-Count")).isEqualTo("2");
    }

    @Test
    void aPeriodThatIsNotAMonthIs422() throws Exception {
        Tenant tenant = api.makeTenant("period");
        assertProblem(api.post("/v1/billing/invoices/draft", tenant.headers(), Map.of("period", "2026-13")), 422, "validation failed");
        assertProblem(api.post("/v1/billing/invoices/draft", tenant.headers(), Map.of("period", "2026-1")), 422, "validation failed");
        assertThat(api.post("/v1/billing/invoices/draft", tenant.headers(), Map.of("period", "2026-01")).status()).isEqualTo(201);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Put the org halfway through its billing period so a change genuinely prorates. */
    private void backdateHalfAPeriod(Tenant tenant) {
        jdbc.sql("UPDATE subscriptions SET current_period_start = now() - interval '15 days', "
                + "current_period_end = now() + interval '15 days' WHERE organization_id = :org")
            .param("org", UUID.fromString(tenant.orgId())).update();
    }

    private static String lastMonth() {
        return java.time.YearMonth.now(java.time.ZoneOffset.UTC).minusMonths(1).toString();
    }

    private static JsonNode lineOfKind(JsonNode invoice, String kind) {
        for (JsonNode line : invoice.get("lines")) {
            if (line.get("kind").asText().equals(kind)) {
                return line;
            }
        }
        return null;
    }

    private Subscription currentSubscription(UUID organizationId) {
        return subscriptions.currentForOrg(organizationId).orElseThrow();
    }

    private long countCustomers(Tenant tenant) {
        return jdbc.sql("SELECT count(*) FROM billing_customers WHERE organization_id = :org")
            .param("org", UUID.fromString(tenant.orgId())).query(Long.class).single();
    }

    private long countOutbox(String eventType, String aggregateId) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE event_type = :type AND aggregate_id = :id")
            .param("type", eventType).param("id", UUID.fromString(aggregateId)).query(Long.class).single();
    }

    private String audience(String eventType) {
        return jdbc.sql("SELECT audience FROM outbox_events WHERE event_type = :type LIMIT 1")
            .param("type", eventType).query(String.class).single();
    }

    /** Invoices the provider path recorded, rather than the ones we drafted. */
    private List<Map<String, Object>> providerInvoicesOf(UUID organizationId) {
        return jdbc.sql("SELECT id, status, number, total_cents FROM invoices WHERE organization_id = :org AND provider <> :provider "
                + "ORDER BY created_at")
            .param("org", organizationId).param("provider", InvoicingService.SELF_PROVIDER)
            .query((rs, i) -> Map.<String, Object>of("id", rs.getObject("id", UUID.class).toString(), "status", rs.getString("status"),
                "number", String.valueOf(rs.getString("number")), "total_cents", rs.getLong("total_cents")))
            .list();
    }

    private List<Map<String, Object>> invoicesOf(UUID organizationId) {
        return jdbc.sql("SELECT id, status, number, total_cents FROM invoices WHERE organization_id = :org AND provider = :provider "
                + "ORDER BY created_at")
            .param("org", organizationId).param("provider", InvoicingService.SELF_PROVIDER)
            .query((rs, i) -> Map.<String, Object>of("id", rs.getObject("id", UUID.class).toString(), "status", rs.getString("status"),
                "number", String.valueOf(rs.getString("number")), "total_cents", rs.getLong("total_cents")))
            .list();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pendingAdjustments(Tenant tenant) throws Exception {
        String raw = jdbc.sql("SELECT pending_adjustments::text FROM subscriptions WHERE organization_id = :org "
                + "AND status IN ('trialing','active','past_due')")
            .param("org", UUID.fromString(tenant.orgId())).query(String.class).single();
        return json.readValue(raw, List.class);
    }
}
