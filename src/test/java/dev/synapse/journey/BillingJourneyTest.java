package dev.synapse.journey;

import static dev.synapse.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.billing.BillingProvider;
import dev.synapse.billing.BillingProviderRegistry;
import dev.synapse.billing.BillingService;
import dev.synapse.billing.HostedBillingProvider;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.errors.CheckoutRequiredError;
import dev.synapse.core.errors.FeatureNotEntitledError;
import dev.synapse.entitlements.FeatureGate;
import dev.synapse.subscriptions.SubscriptionService;
import dev.synapse.support.ApiClient;
import dev.synapse.support.ApiClient.Res;
import dev.synapse.support.ApiClient.Tenant;
import dev.synapse.support.PostgresTestSupport;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Milestone-3 journeys over a real Postgres: catalog → free subscription →
 * consume until 402 → trial → paid limits → proration → idempotent record →
 * batch rollback → gauges + seats → operator grants → key auth metering →
 * ten parallel consumers against a three-slot limit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "synapse.bootstrap-admin-email=" + ApiJourneyTest.ADMIN_EMAIL,
    "synapse.bootstrap-admin-password=" + ApiJourneyTest.ADMIN_PASSWORD,
    "synapse.refresh-reuse-grace-seconds=0"
})
class BillingJourneyTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired SubscriptionService subscriptions;
    @Autowired FeatureGate featureGate;
    @Autowired SynapseProperties props;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    // ── Catalog + default subscription ───────────────────────────────────────────

    @Test
    void plansAreAPaginatedCatalogAndANewOrgIsOnFree() throws Exception {
        Tenant tenant = api.makeTenant("plans");
        Res plans = api.get("/v1/plans", tenant.headers());
        assertThat(plans.status()).isEqualTo(200);
        assertThat(plans.header("X-Total-Count")).isEqualTo("3"); // enterprise is not public
        List<String> keys = plans.body().findValues("key").stream().map(JsonNode::asText).toList();
        assertThat(keys).containsExactly("free", "starter", "pro");
        JsonNode free = plans.body().get(0);
        assertThat(free.get("price_cents").asLong()).isZero();
        assertThat(free.get("is_public").asBoolean()).isTrue();
        assertThat(free.get("is_custom").asBoolean()).isFalse();
        assertThat(free.get("trial_days").asInt()).isZero();
        assertThat(free.get("features").findValues("feature_key").stream().map(JsonNode::asText)).containsExactly("api_access", "basic_dashboard");
        JsonNode apiLimit = free.get("limits").findValues("metric").stream().map(JsonNode::asText).toList().indexOf("api_requests") >= 0
            ? free.get("limits").get(free.get("limits").findValues("metric").stream().map(JsonNode::asText).toList().indexOf("api_requests")) : null;
        assertThat(apiLimit.get("limit_value").asLong()).isEqualTo(10000);
        assertThat(apiLimit.get("soft_limit_ratio").asDouble()).isEqualTo(0.8);
        assertThat(api.get("/v1/plans?limit=1", tenant.bearer()).body().size()).isEqualTo(1);
        assertProblem(api.get("/v1/plans?limit=101", tenant.headers()), 422, "validation failed");
        assertProblem(api.get("/v1/plans", Map.of()), 401, "unauthorized");

        Res current = api.get("/v1/subscription", tenant.headers());
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().at("/subscription/plan/key").asText()).isEqualTo("free");
        assertThat(current.body().at("/subscription/status").asText()).isEqualTo("active");
        assertThat(current.body().at("/subscription/plan_snapshot/key").asText()).isEqualTo("free");
        assertThat(current.body().at("/subscription/plan_snapshot/limits/api_requests").asLong()).isEqualTo(10000);
        assertThat(current.body().at("/subscription/cancel_at_period_end").asBoolean()).isFalse();
        assertThat(current.body().at("/entitlements/plan_key").asText()).isEqualTo("free");
        assertThat(current.body().at("/entitlements/subscription_status").asText()).isEqualTo("active");
        assertThat(current.body().get("usage").isArray()).isTrue();
        assertThat(current.body().get("usage").findValues("metric").stream().map(JsonNode::asText)).contains("users"); // the seat gauge
        assertThat(outboxPayload("subscription.activated", UUID.fromString(current.body().at("/subscription/id").asText()))).containsEntry("plan_key", "free");

        Res entitlements = api.get("/v1/entitlements", tenant.headers());
        assertThat(entitlements.status()).isEqualTo(200);
        assertThat(entitlements.body().at("/limits/api_requests/value").asLong()).isEqualTo(10000);
        assertThat(entitlements.body().at("/limits/api_requests/soft_limit_ratio").asDouble()).isEqualTo(0.8);
        assertThat(entitlements.body().at("/limits/users/value").asLong()).isEqualTo(3);
        assertThat(entitlements.body().get("features").findValues("").isEmpty()).isTrue();
        assertThat(entitlements.body().get("features").toString()).isEqualTo("[\"api_access\",\"basic_dashboard\"]");
        // billing:read is required for the subscription view, membership alone for entitlements
        Tenant stranger = api.makeTenant("stranger");
        assertProblem(api.get("/v1/subscription", Map.of("Authorization", "Bearer " + stranger.accessToken(), "X-Org-Id", tenant.orgId())), 404, "not found");
    }

    // ── Consume → 402 → trial → paid → proration ─────────────────────────────────

    @Test
    void consumeUntilTheLimitThenTrialThenPaidPlansWithProration() throws Exception {
        Tenant tenant = api.makeTenant("consume");
        Res check = api.get("/v1/usage/check?metric=api_requests&quantity=1", tenant.headers());
        assertThat(check.status()).isEqualTo(200);
        assertThat(check.body().get("within_limit").asBoolean()).isTrue();
        assertThat(check.body().get("soft_limit").asLong()).isEqualTo(8000);
        assertProblem(api.get("/v1/usage/check?metric=api_requests&quantity=0", tenant.headers()), 422, "validation failed");
        assertProblem(api.get("/v1/usage/check", tenant.headers()), 422, "validation failed");
        JsonNode unknown = assertProblem(api.get("/v1/usage/check?metric=warp_drives", tenant.headers()), 422, "unknown metric");
        assertThat(unknown.get("metric").asText()).isEqualTo("warp_drives");

        Res big = api.post("/v1/usage/consume", tenant.headers(), Map.of("events", List.of(Map.of("metric", "api_requests", "quantity", 9999))));
        assertThat(big.status()).isEqualTo(200);
        assertThat(big.body().get("total").asLong()).isEqualTo(9999);
        assertThat(big.body().get("limit").asLong()).isEqualTo(10000);
        assertThat(big.body().get("remaining").asLong()).isEqualTo(1);
        assertThat(big.body().get("within_limit").asBoolean()).isTrue();
        assertThat(big.body().get("deduplicated").asBoolean()).isFalse();
        assertThat(countOutbox("usage.soft_limit_reached", tenant.orgId())).isEqualTo(1); // crossed 80% exactly once
        assertThat(countOutbox("usage.hard_limit_reached", tenant.orgId())).isZero();

        Res last = api.post("/v1/usage/consume", tenant.headers(), Map.of("events", List.of(Map.of("metric", "api_requests"))));
        assertThat(last.body().get("total").asLong()).isEqualTo(10000);
        assertThat(last.body().get("remaining").asLong()).isZero();
        assertThat(countOutbox("usage.hard_limit_reached", tenant.orgId())).isEqualTo(1);
        assertThat(countOutbox("usage.soft_limit_reached", tenant.orgId())).isEqualTo(1);

        JsonNode exceeded = assertProblem(api.post("/v1/usage/consume", tenant.headers(), Map.of("events", List.of(Map.of("metric", "api_requests")))), 402, "usage limit exceeded");
        assertThat(exceeded.get("metric").asText()).isEqualTo("api_requests");
        assertThat(exceeded.get("limit").asLong()).isEqualTo(10000);
        assertThat(exceeded.get("used").asLong()).isEqualTo(10000);
        assertThat(exceeded.get("attempted").asLong()).isEqualTo(1);
        assertThat(exceeded.get("upgrade_url").asText()).isEqualTo("/dashboard/billing");
        assertThat(used(tenant, "api_requests")).isEqualTo(10000); // the breach rolled back
        assertThat(jdbc.sql("SELECT count(*) FROM usage_events WHERE organization_id = :org").param("org", UUID.fromString(tenant.orgId())).query(Long.class).single()).isEqualTo(2);
        JsonNode summary = api.get("/v1/usage/summary", tenant.headers()).body();
        assertThat(summary.get("period").asText()).matches("\\d{4}-\\d{2}-01");
        JsonNode row = summary.get("metrics").findValues("metric").stream().map(JsonNode::asText).toList().indexOf("api_requests") >= 0
            ? summary.get("metrics").get(summary.get("metrics").findValues("metric").stream().map(JsonNode::asText).toList().indexOf("api_requests")) : null;
        assertThat(row.get("used").asLong()).isEqualTo(10000);
        assertThat(row.get("within_limit").asBoolean()).isFalse(); // 10000 + 1 > 10000
        assertThat(row.get("soft_limit_breached").asBoolean()).isTrue();

        // Trial: full plan features, fresh limits, 409 on a second one, 409 on a plan without a trial period.
        Res trial = api.post("/v1/subscription/trial", tenant.headers(), Map.of("plan_key", "starter"));
        assertThat(trial.status()).isEqualTo(201);
        assertThat(trial.text("status")).isEqualTo("trialing");
        assertThat(trial.body().at("/plan/key").asText()).isEqualTo("starter");
        assertThat(trial.body().get("trial_ends_at").isNull()).isFalse();
        assertThat(outboxPayload("subscription.trial_started", UUID.fromString(trial.text("id")))).containsEntry("plan_key", "starter").containsEntry("status", "trialing");
        Res entitlements = api.get("/v1/entitlements", tenant.headers());
        assertThat(entitlements.text("subscription_status")).isEqualTo("trialing");
        assertThat(entitlements.text("plan_key")).isEqualTo("starter");
        assertThat(entitlements.body().get("features").findValues("").isEmpty()).isTrue();
        assertThat(entitlements.body().get("features").toString()).contains("\"reports\"");
        assertThat(entitlements.body().at("/limits/api_requests/value").asLong()).isEqualTo(100000);
        assertThat(api.post("/v1/usage/consume", tenant.headers(), Map.of("events", List.of(Map.of("metric", "api_requests")))).body().get("total").asLong()).isEqualTo(10001);
        assertProblem(api.post("/v1/subscription/trial", tenant.headers(), Map.of("plan_key", "pro")), 409, "trial not allowed");
        assertProblem(api.post("/v1/subscription/trial", tenant.headers(), Map.of("plan_key", "")), 422, "validation failed");
        Tenant noTrial = api.makeTenant("notrial");
        assertProblem(api.post("/v1/subscription/trial", noTrial.headers(), Map.of("plan_key", "free")), 409, "trial not allowed");

        // Cancel at period end → resume → resume again is 404 → cancel immediately collapses entitlements.
        Res cancelled = api.post("/v1/subscription/cancel", tenant.headers(), Map.of("at_period_end", true));
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("cancel_at_period_end").asBoolean()).isTrue();
        assertThat(cancelled.text("status")).isEqualTo("trialing");
        Res resumed = api.post("/v1/subscription/resume", tenant.headers(), null);
        assertThat(resumed.status()).isEqualTo(200);
        assertThat(resumed.body().get("cancel_at_period_end").asBoolean()).isFalse();
        assertProblem(api.post("/v1/subscription/resume", tenant.headers(), null), 404, "subscription not found");
        Res immediate = api.post("/v1/subscription/cancel", tenant.headers(), Map.of("at_period_end", false));
        assertThat(immediate.text("status")).isEqualTo("canceled");
        assertThat(immediate.body().get("canceled_at").isNull()).isFalse();
        JsonNode collapsed = api.get("/v1/entitlements", tenant.headers()).body();
        assertThat(collapsed.get("features").size()).isZero();
        assertThat(collapsed.get("subscription_status").isNull()).isTrue();
        assertThat(api.get("/v1/subscription", tenant.headers()).body().get("subscription").isNull()).isTrue();
        assertProblem(api.post("/v1/subscription/cancel", tenant.headers(), Map.of()), 404, "subscription not found");

        // Local (manual) provider: no subscription → a fresh active one; free→paid resets, paid→paid keeps the period and prorates.
        assertProblem(api.post("/v1/subscription/change", tenant.headers(), Map.of("plan_key", "nope")), 404, "plan not found");
        Res starter = api.post("/v1/subscription/change", tenant.headers(), Map.of("plan_key", "starter"));
        assertThat(starter.status()).isEqualTo(200);
        assertThat(starter.text("status")).isEqualTo("active");
        assertThat(starter.body().at("/plan/key").asText()).isEqualTo("starter");
        assertThat(pendingAdjustments(tenant)).isEmpty();
        assertThat(jdbc.sql("SELECT provider FROM subscriptions WHERE id = :id").param("id", UUID.fromString(starter.text("id"))).query(String.class).single()).isEqualTo("manual");

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("UPDATE subscriptions SET current_period_start = :start, current_period_end = :end WHERE id = :id")
            .param("start", now.minus(Duration.ofDays(15))).param("end", now.plus(Duration.ofDays(15))).param("id", UUID.fromString(starter.text("id"))).update();
        Res pro = api.post("/v1/subscription/change", tenant.headers(), Map.of("plan_key", "pro"));
        assertThat(pro.status()).isEqualTo(200);
        assertThat(pro.body().at("/plan/key").asText()).isEqualTo("pro");
        assertThat(pro.text("id")).isEqualTo(starter.text("id"));
        assertThat(Duration.between(now.plus(Duration.ofDays(15)).toInstant(), OffsetDateTime.parse(pro.text("current_period_end")).toInstant()).abs()).isLessThan(Duration.ofSeconds(1));
        List<Map<String, Object>> adjustments = pendingAdjustments(tenant);
        assertThat(adjustments).hasSize(1);
        Map<String, Object> credit = adjustments.get(0);
        assertThat(credit).containsEntry("kind", "proration").containsEntry("from_plan", "starter").containsEntry("to_plan", "pro");
        assertThat(((Number) credit.get("amount_cents")).longValue()).isBetween(-75100L, -74900L); // half the period at the old price
        assertThat((String) credit.get("description")).startsWith("Plan change starter → pro: credit for 50.0%");
        assertThat(outboxPayload("subscription.plan_changed", UUID.fromString(pro.text("id")))).containsEntry("from_plan", "starter").containsEntry("to_plan", "pro");

        jdbc.sql("UPDATE subscriptions SET current_period_start = :start, current_period_end = :end, pending_adjustments = '[]'::jsonb WHERE id = :id")
            .param("start", now.minus(Duration.ofDays(15))).param("end", now.plus(Duration.ofDays(15))).param("id", UUID.fromString(starter.text("id"))).update();
        assertThat(api.post("/v1/subscription/change", tenant.headers(), Map.of("plan_key", "starter")).status()).isEqualTo(200);
        assertThat(((Number) pendingAdjustments(tenant).get(0).get("amount_cents")).longValue()).isBetween(74900L, 75100L); // downgrade charges

        // free → paid starts a fresh cycle today
        Tenant fresh = api.makeTenant("fresh");
        String freeId = api.get("/v1/subscription", fresh.headers()).body().at("/subscription/id").asText();
        jdbc.sql("UPDATE subscriptions SET current_period_start = :start, current_period_end = :end WHERE id = :id")
            .param("start", now.minus(Duration.ofDays(20))).param("end", now.plus(Duration.ofDays(10))).param("id", UUID.fromString(freeId)).update();
        Res upgraded = api.post("/v1/subscription/change", fresh.headers(), Map.of("plan_key", "starter"));
        assertThat(OffsetDateTime.parse(upgraded.text("current_period_start")).toInstant()).isAfter(now.minus(Duration.ofMinutes(1)).toInstant());
        assertThat(pendingAdjustments(fresh)).isEmpty();
        // a member without billing:manage cannot change plans
        String memberEmail = "member-" + ApiClient.uid() + "@conformance.example.com";
        Res memberReg = api.register(memberEmail, "Member");
        String membershipId = api.post("/v1/orgs/current/members/invite", fresh.headers(), Map.of("email", memberEmail)).text("id");
        String token = (String) outboxPayload("member.invite_email", UUID.fromString(membershipId)).get("invite_token");
        Map<String, String> member = new HashMap<>(Map.of("Authorization", "Bearer " + memberReg.body().at("/tokens/access_token").asText()));
        assertThat(api.post("/v1/auth/accept-invite", member, Map.of("token", token)).status()).isEqualTo(200);
        member.put("X-Org-Id", fresh.orgId());
        assertProblem(api.post("/v1/subscription/change", member, Map.of("plan_key", "pro")), 403, "permission denied");
        assertProblem(api.get("/v1/subscription", member), 403, "permission denied");
        assertThat(api.get("/v1/entitlements", member).status()).isEqualTo(200);
    }

    // ── Hosted provider branch (the milestone-4 seam) ────────────────────────────

    @Test
    void hostedProvidersRequireACheckoutUnlessTheyHoldTheSubscription() throws Exception {
        Tenant tenant = api.makeTenant("hosted");
        BillingProviderRegistry stripe = new BillingProviderRegistry(props) {
            @Override
            public BillingProvider current() {
                return HostedBillingProvider.STRIPE;
            }
        };
        BillingService billing = new BillingService(subscriptions, stripe);
        UUID orgId = UUID.fromString(tenant.orgId());
        assertThatThrownBy(() -> billing.changePlan(orgId, "pro")).isInstanceOf(CheckoutRequiredError.class)
            .satisfies(t -> {
                CheckoutRequiredError e = (CheckoutRequiredError) t;
                assertThat(e.status()).isEqualTo(409);
                assertThat(e.extras()).containsEntry("plan_key", "pro").containsEntry("checkout_url", "/v1/billing/checkout");
            });
        assertThat(api.get("/v1/subscription", tenant.headers()).body().at("/subscription/plan/key").asText()).isEqualTo("free");
        jdbc.sql("UPDATE subscriptions SET provider = 'stripe', provider_subscription_id = :ref WHERE organization_id = :org")
            .param("ref", "sub_" + ApiClient.uid()).param("org", orgId).update();
        assertThatThrownBy(() -> billing.changePlan(orgId, "pro")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("milestone 4");
        assertThat(BillingProviderRegistry.locallyBilledProviderNames()).containsExactly("manual", "paddle", "xendit", "paymongo");
    }

    // ── Idempotency + batches ────────────────────────────────────────────────────

    @Test
    void recordIsIdempotentAndBatchesAreAllOrNothing() throws Exception {
        Tenant tenant = api.makeTenant("idem");
        String key = "evt-" + ApiClient.uid();
        Map<String, Object> body = Map.of("events", List.of(Map.of("metric", "api_requests", "quantity", 2, "idempotency_key", key)));
        Res first = api.post("/v1/usage/events", tenant.headers(), body);
        Res second = api.post("/v1/usage/events", tenant.headers(), body);
        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(first.body().get(0).get("deduplicated").asBoolean()).isFalse();
        assertThat(first.body().get(0).get("limit").isNull()).isTrue(); // record never enforces
        assertThat(second.body().get(0).get("deduplicated").asBoolean()).isTrue();
        assertThat(second.body().get(0).get("total").asLong()).isEqualTo(2);
        assertThat(second.body().get(0).get("quantity").asLong()).isEqualTo(2);
        assertThat(used(tenant, "api_requests")).isEqualTo(2);

        Map<String, Object> consumeBody = Map.of("events", List.of(Map.of("metric", "api_requests", "quantity", 5, "idempotency_key", "c-" + key)));
        Res consumed = api.post("/v1/usage/consume", tenant.headers(), consumeBody);
        Res replay = api.post("/v1/usage/consume", tenant.headers(), consumeBody);
        assertThat(consumed.body().get("total").asLong()).isEqualTo(7);
        assertThat(replay.body().get("deduplicated").asBoolean()).isTrue();
        assertThat(replay.body().get("total").asLong()).isEqualTo(7);
        assertThat(replay.body().get("limit").asLong()).isEqualTo(10000);
        assertThat(replay.body().get("within_limit").asBoolean()).isTrue();

        // keys are per organization
        Tenant other = api.makeTenant("idem-other");
        assertThat(api.post("/v1/usage/events", other.headers(), body).body().get(0).get("deduplicated").asBoolean()).isFalse();

        // consume takes exactly one event; a batch counts every event or none
        JsonNode multi = assertProblem(api.post("/v1/usage/consume", tenant.headers(),
            Map.of("events", List.of(Map.of("metric", "api_requests"), Map.of("metric", "api_requests")))), 422, "validation failed");
        assertThat(multi.get("batch_url").asText()).isEqualTo("/v1/usage/consume-batch");
        assertThat(used(tenant, "api_requests")).isEqualTo(7);
        Res batch = api.post("/v1/usage/consume-batch", tenant.headers(),
            Map.of("events", List.of(Map.of("metric", "api_requests", "quantity", 3), Map.of("metric", "api_requests", "quantity", 4))));
        assertThat(batch.status()).isEqualTo(200);
        assertThat(batch.body().findValues("total").stream().map(JsonNode::asLong)).containsExactly(10L, 14L);
        JsonNode breach = assertProblem(api.post("/v1/usage/consume-batch", tenant.headers(),
            Map.of("events", List.of(Map.of("metric", "api_requests", "quantity", 4000), Map.of("metric", "api_requests", "quantity", 4000),
                Map.of("metric", "api_requests", "quantity", 4000)))), 402, "usage limit exceeded");
        assertThat(breach.get("metric").asText()).isEqualTo("api_requests");
        assertThat(breach.get("limit").asLong()).isEqualTo(10000);
        assertThat(used(tenant, "api_requests")).isEqualTo(14); // nothing in the batch counted

        // a breached consume does not burn its key: raising the limit lets the same key succeed
        Map<String, Object> huge = Map.of("events", List.of(Map.of("metric", "api_requests", "quantity", 20000, "idempotency_key", "big-" + key)));
        assertProblem(api.post("/v1/usage/consume", tenant.headers(), huge), 402, "usage limit exceeded");
        Map<String, String> platform = platform();
        Res grant = api.post("/v1/admin/orgs/" + tenant.orgId() + "/entitlements/grants", platform,
            Map.of("feature_key", "limit:api_requests", "source", "addon", "limit_value", 50000));
        assertThat(grant.status()).isEqualTo(201);
        Res retried = api.post("/v1/usage/consume", tenant.headers(), huge);
        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.body().get("deduplicated").asBoolean()).isFalse();
        assertThat(retried.body().get("total").asLong()).isEqualTo(20014);
        assertThat(retried.body().get("limit").asLong()).isEqualTo(50000);

        // counters reject gauge metrics, unknown metrics are 422 unknown_metric, bodies are validated
        for (String path : List.of("/v1/usage/events", "/v1/usage/consume")) {
            JsonNode doc = assertProblem(api.post(path, tenant.headers(), Map.of("events", List.of(Map.of("metric", "projects")))), 422, "validation failed");
            assertThat(doc.get("kind").asText()).isEqualTo("gauge");
        }
        assertProblem(api.post("/v1/usage/events", tenant.headers(), Map.of("events", List.of(Map.of("metric", "warp_drives")))), 422, "unknown metric");
        assertProblem(api.post("/v1/usage/events", tenant.headers(), Map.of("events", List.of())), 422, "validation failed");
        assertProblem(api.post("/v1/usage/events", tenant.headers(), Map.of("events", List.of(Map.of("metric", "api_requests", "quantity", 0)))), 422, "validation failed");
        assertProblem(api.post("/v1/usage/events", tenant.headers(), Map.of("events", List.of(Map.of("quantity", 1)))), 422, "validation failed");
    }

    // ── Gauges + seats ───────────────────────────────────────────────────────────

    @Test
    void gaugesAreLevelsAndSeatsFollowMemberships() throws Exception {
        Tenant tenant = api.makeTenant("gauge");
        Res set = api.post("/v1/usage/gauge", tenant.headers(), Map.of("metric", "projects", "value", 2));
        assertThat(set.status()).isEqualTo(200);
        assertThat(set.body().get("total").asLong()).isEqualTo(2);
        assertThat(set.body().get("limit").asLong()).isEqualTo(2);
        assertThat(set.body().get("remaining").asLong()).isZero();
        assertThat(set.body().get("within_limit").asBoolean()).isTrue();
        assertThat(api.post("/v1/usage/gauge", tenant.headers(), Map.of("metric", "projects", "delta", -1)).body().get("total").asLong()).isEqualTo(1);
        assertThat(api.post("/v1/usage/gauge", tenant.headers(), Map.of("metric", "projects", "delta", -5)).body().get("total").asLong()).isZero(); // never below zero
        assertThat(used(tenant, "projects")).isZero();

        // Free plan: projects = 2. The third one is a 402 and the level stays put; `value` is a sync, never refused.
        for (int i = 0; i < 2; i++) {
            assertThat(api.post("/v1/usage/gauge", tenant.headers(), Map.of("metric", "projects", "delta", 1)).status()).isEqualTo(200);
        }
        JsonNode full = assertProblem(api.post("/v1/usage/gauge", tenant.headers(), Map.of("metric", "projects", "delta", 1)), 402, "usage limit exceeded");
        assertThat(full.get("metric").asText()).isEqualTo("projects");
        assertThat(full.get("limit").asLong()).isEqualTo(2);
        assertThat(full.get("used").asLong()).isEqualTo(2);
        assertThat(used(tenant, "projects")).isEqualTo(2);
        Res synced = api.post("/v1/usage/gauge", tenant.headers(), Map.of("metric", "projects", "value", 5));
        assertThat(synced.status()).isEqualTo(200);
        assertThat(synced.body().get("within_limit").asBoolean()).isFalse();

        // a level, not a per-month flow: it shows up in any period's summary
        JsonNode oldPeriod = api.get("/v1/usage/summary?period=2001-01", tenant.headers()).body();
        assertThat(oldPeriod.get("period").asText()).isEqualTo("2001-01-01");
        assertThat(metricRow(oldPeriod, "projects").get("used").asLong()).isEqualTo(5);
        assertThat(metricRow(oldPeriod, "api_requests")).isNull(); // unmetered metrics are absent
        assertProblem(api.get("/v1/usage/summary?period=2001-1", tenant.headers()), 422, "validation failed");

        Res storage = api.post("/v1/usage/gauge", tenant.headers(), Map.of("metric", "storage_bytes", "value", 1024));
        assertThat(storage.body().get("total").asLong()).isEqualTo(1024);
        assertThat(api.post("/v1/usage/gauge", tenant.headers(), Map.of("metric", "storage_bytes", "delta", -24)).body().get("total").asLong()).isEqualTo(1000);

        JsonNode counter = assertProblem(api.post("/v1/usage/gauge", tenant.headers(), Map.of("metric", "api_requests", "value", 1)), 422, "validation failed");
        assertThat(counter.get("kind").asText()).isEqualTo("counter");
        assertProblem(api.post("/v1/usage/gauge", tenant.headers(), Map.of("metric", "projects")), 422, "validation failed");
        assertProblem(api.post("/v1/usage/gauge", tenant.headers(), Map.of("metric", "projects", "value", 1, "delta", 1)), 422, "validation failed");
        assertProblem(api.post("/v1/usage/gauge", tenant.headers(), Map.of("metric", "projects", "value", -1)), 422, "validation failed");

        // Seats: the owner holds one; invites hold a seat; the free plan caps at 3; removal frees it.
        assertThat(used(tenant, "users")).isEqualTo(1);
        Res inviteA = api.post("/v1/orgs/current/members/invite", tenant.headers(), Map.of("email", "a-" + ApiClient.uid() + "@example.com"));
        assertThat(inviteA.status()).isEqualTo(201);
        assertThat(used(tenant, "users")).isEqualTo(2);
        assertThat(api.post("/v1/orgs/current/members/invite", tenant.headers(), Map.of("email", "b-" + ApiClient.uid() + "@example.com")).status()).isEqualTo(201);
        assertThat(used(tenant, "users")).isEqualTo(3);
        JsonNode seats = assertProblem(api.post("/v1/orgs/current/members/invite", tenant.headers(), Map.of("email", "c-" + ApiClient.uid() + "@example.com")), 402, "usage limit exceeded");
        assertThat(seats.get("metric").asText()).isEqualTo("users");
        assertThat(seats.get("limit").asLong()).isEqualTo(3);
        assertThat(seats.get("used").asLong()).isEqualTo(3);
        assertThat(seats.get("upgrade_url").asText()).isEqualTo("/dashboard/billing");
        assertThat(api.get("/v1/orgs/current/members", tenant.headers()).body().at("/meta/total").asInt()).isEqualTo(3);
        assertThat(api.delete("/v1/memberships/" + inviteA.text("id"), tenant.headers()).status()).isEqualTo(204);
        assertThat(used(tenant, "users")).isEqualTo(2);
        assertThat(api.post("/v1/orgs/current/members/invite", tenant.headers(), Map.of("email", "c-" + ApiClient.uid() + "@example.com")).status()).isEqualTo(201);
    }

    // ── Operator surface + feature gate ──────────────────────────────────────────

    @Test
    void operatorGrantsAndTheFeatureGate() throws Exception {
        Tenant tenant = api.makeTenant("grants");
        Map<String, String> platform = platform();
        String base = "/v1/admin/orgs/" + tenant.orgId() + "/entitlements";
        assertProblem(api.post(base + "/grants", tenant.headers(), Map.of("feature_key", "sso", "source", "beta")), 404, "not found"); // invisible to tenants
        assertProblem(api.get(base, tenant.headers()), 404, "not found");
        assertProblem(api.post(base + "/grants", platform, Map.of("feature_key", "sso", "source", "vip")), 422, "validation failed");
        assertProblem(api.post(base + "/grants", platform, Map.of("feature_key", "sso", "source", "beta", "duration_days", 0)), 422, "validation failed");

        Res grant = api.post(base + "/grants", platform, Map.of("feature_key", "sso", "source", "beta", "duration_days", 7, "note", "beta cohort"));
        assertThat(grant.status()).isEqualTo(201);
        assertThat(grant.text("feature_key")).isEqualTo("sso");
        assertThat(grant.text("source")).isEqualTo("beta");
        UUID grantId = UUID.fromString(grant.text("id"));
        Map<String, Object> granted = outboxPayload("entitlement.granted", grantId);
        assertThat(granted).containsEntry("feature_key", "sso").containsEntry("source", "beta");
        assertThat(granted.get("ends_at")).isNotNull();
        assertThat(jdbc.sql("SELECT created_by_user_id IS NOT NULL FROM entitlements WHERE id = :id").param("id", grantId).query(Boolean.class).single()).isTrue();

        Res effective = api.get(base, platform);
        assertThat(effective.status()).isEqualTo(200);
        assertThat(effective.body().get("features").toString()).contains("\"sso\"");
        assertThat(api.get("/v1/entitlements", tenant.headers()).body().get("features").toString()).contains("\"sso\"");

        Tenant other = api.makeTenant("grants-other");
        assertProblem(api.delete("/v1/admin/orgs/" + other.orgId() + "/entitlements/grants/" + grantId, platform), 404, "entitlement not found");
        assertProblem(api.delete(base + "/grants/" + UUID.randomUUID(), platform), 404, "entitlement not found");
        assertThat(api.delete(base + "/grants/" + grantId, platform).status()).isEqualTo(204);
        assertThat(api.get("/v1/entitlements", tenant.headers()).body().get("features").toString()).doesNotContain("\"sso\"");
        assertThat(outboxPayload("entitlement.revoked", grantId)).containsEntry("feature_key", "sso");

        // kill switch: a winning enabled=false grant removes a plan feature
        assertThat(api.post(base + "/grants", platform, Map.of("feature_key", "basic_dashboard", "source", "override", "enabled", false)).status()).isEqualTo(201);
        assertThat(api.get("/v1/entitlements", tenant.headers()).body().get("features").toString()).isEqualTo("[\"api_access\"]");

        // limit grants override the plan cap and keep the soft ratio
        assertThat(api.post(base + "/grants", platform, Map.of("feature_key", "limit:api_requests", "source", "addon", "limit_value", 3)).status()).isEqualTo(201);
        JsonNode limits = api.get("/v1/entitlements", tenant.headers()).body().get("limits");
        assertThat(limits.at("/api_requests/value").asLong()).isEqualTo(3);
        assertThat(limits.at("/api_requests/soft_limit_ratio").asDouble()).isEqualTo(0.8);

        // the feature gate later milestones hang routes on
        UUID orgId = UUID.fromString(tenant.orgId());
        assertThatThrownBy(() -> featureGate.require(orgId, "agents")).isInstanceOf(FeatureNotEntitledError.class)
            .satisfies(t -> {
                FeatureNotEntitledError e = (FeatureNotEntitledError) t;
                assertThat(e.status()).isEqualTo(403);
                assertThat(e.extras()).containsEntry("feature", "agents").containsEntry("current_plan", "free").containsEntry("upgrade_url", "/dashboard/billing");
                assertThat(String.valueOf(e.extras().get("available_in"))).isEqualTo("[enterprise, pro]");
            });
        assertThat(api.post(base + "/grants", platform, Map.of("feature_key", "agents", "source", "beta")).status()).isEqualTo(201);
        featureGate.require(orgId, "agents"); // passes now
    }

    // ── API keys meter api_requests ───────────────────────────────────────────────

    @Test
    void keyAuthenticatedCallsMeterApiRequests() throws Exception {
        Tenant tenant = api.makeTenant("keys");
        Res created = api.post("/v1/api-keys", tenant.headers(), Map.of("name", "ci", "scopes", List.of("usage:read")));
        assertThat(created.status()).isEqualTo(201);
        Map<String, String> asKey = Map.of("Authorization", "Bearer " + created.text("key"));
        assertThat(api.get("/v1/usage/check?metric=api_requests", asKey).status()).isEqualTo(200);
        assertThat(used(tenant, "api_requests")).isEqualTo(1); // the call itself was metered, best-effort
        Res consumed = api.post("/v1/usage/consume", asKey, Map.of("events", List.of(Map.of("metric", "api_requests", "quantity", 1))));
        assertThat(consumed.status()).isEqualTo(200);
        assertThat(consumed.body().get("total").asLong()).isEqualTo(3); // metered request + consumed unit
        assertProblem(api.get("/v1/orgs/current/members", asKey), 403, "permission denied");
        assertThat(used(tenant, "api_requests")).isEqualTo(4); // even a 403 was a key-authenticated call
        assertThat(api.delete("/v1/api-keys/" + created.text("id"), tenant.headers()).status()).isEqualTo(204);
        assertProblem(api.get("/v1/usage/summary", asKey), 401, "unauthorized");
        assertThat(used(tenant, "api_requests")).isEqualTo(4);
    }

    // ── Concurrency: consumers never overshoot ────────────────────────────────────

    @Test
    void tenParallelConsumersNeverExceedAThreeSlotLimit() throws Exception {
        Tenant tenant = api.makeTenant("race");
        assertThat(api.post("/v1/admin/orgs/" + tenant.orgId() + "/entitlements/grants", platform(),
            Map.of("feature_key", "limit:api_requests", "source", "addon", "limit_value", 3)).status()).isEqualTo(201);
        int workers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return api.post("/v1/usage/consume", tenant.headers(), Map.of("events", List.of(Map.of("metric", "api_requests")))).status();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(3);
            assertThat(statuses.stream().filter(s -> s == 402).count()).isEqualTo(7);
        } finally {
            pool.shutdownNow();
        }
        assertThat(used(tenant, "api_requests")).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM usage_events WHERE organization_id = :org AND metric = 'api_requests'")
            .param("org", UUID.fromString(tenant.orgId())).query(Long.class).single()).isEqualTo(3);

        // the same race through the idempotency ledger: one key, four concurrent retries, counted once
        String key = "race-" + ApiClient.uid();
        Map<String, Object> body = Map.of("events", List.of(Map.of("metric", "ai_tokens", "quantity", 9, "idempotency_key", key)));
        ExecutorService retries = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> flags = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                flags.add(retries.submit(() -> {
                    go.await();
                    Res res = api.post("/v1/usage/events", tenant.headers(), body);
                    assertThat(res.status()).isEqualTo(201);
                    return res.body().get(0).get("deduplicated").asBoolean();
                }));
            }
            go.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> f : flags) {
                results.add(f.get());
            }
            assertThat(results.stream().filter(b -> !b).count()).isEqualTo(1);
        } finally {
            retries.shutdownNow();
        }
        assertThat(used(tenant, "ai_tokens")).isEqualTo(9);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private Map<String, String> platform() throws Exception {
        Res login = api.post("/v1/auth/login", Map.of(), Map.of("email", ApiJourneyTest.ADMIN_EMAIL, "password", ApiJourneyTest.ADMIN_PASSWORD));
        assertThat(login.status()).isEqualTo(200);
        return Map.of("Authorization", "Bearer " + login.body().at("/tokens/access_token").asText());
    }

    private long used(Tenant tenant, String metric) throws Exception {
        Res res = api.get("/v1/usage/check?metric=" + metric, tenant.headers());
        assertThat(res.status()).as(res.body() == null ? "" : res.body().toString()).isEqualTo(200);
        return res.body().get("used").asLong();
    }

    private static JsonNode metricRow(JsonNode summary, String metric) {
        for (JsonNode row : summary.get("metrics")) {
            if (row.get("metric").asText().equals(metric)) {
                return row;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pendingAdjustments(Tenant tenant) throws Exception {
        String raw = jdbc.sql("SELECT pending_adjustments::text FROM subscriptions WHERE organization_id = :org AND status IN ('trialing','active','past_due')")
            .param("org", UUID.fromString(tenant.orgId())).query(String.class).single();
        return json.readValue(raw, List.class);
    }

    private long countOutbox(String eventType, String orgId) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE event_type = :type AND organization_id = :org")
            .param("type", eventType).param("org", UUID.fromString(orgId)).query(Long.class).single();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outboxPayload(String eventType, UUID aggregateId) throws Exception {
        String payload = jdbc.sql("SELECT payload::text FROM outbox_events WHERE event_type = :type AND aggregate_id = :id ORDER BY created_at DESC LIMIT 1")
            .param("type", eventType).param("id", aggregateId).query(String.class).single();
        return json.readValue(payload, Map.class);
    }
}
