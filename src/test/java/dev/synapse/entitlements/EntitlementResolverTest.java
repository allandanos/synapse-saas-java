package dev.synapse.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import dev.synapse.entitlements.EntitlementResolver.EffectiveEntitlements;
import dev.synapse.entitlements.EntitlementResolver.EntitlementGrant;
import dev.synapse.entitlements.EntitlementResolver.EntitlementInputs;
import dev.synapse.entitlements.EntitlementResolver.Limit;
import dev.synapse.entitlements.EntitlementResolver.Overage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The pure function that turns (plan + subscription + grants) into the effective
 * feature/limit set. Every pricing behaviour the framework promises is pinned here
 * (reference: {@code tests/unit/entitlements/test_resolver.py}).
 */
class EntitlementResolverTest {

    static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    static final Set<String> PLAN_FEATURES = Set.of("basic_dashboard", "api_access", "advanced_reports");
    static final Map<String, Limit> PLAN_LIMITS = Map.of(
        "users", Limit.of(10L, null),
        "api_requests", Limit.of(100_000L, 0.8),
        "storage_bytes", Limit.of(null, null)); // unlimited

    static EntitlementInputs.Builder inputs() {
        return EntitlementInputs.builder(UUID.randomUUID(), NOW)
            .planKey("pro").subscriptionStatus("active").planFeatures(PLAN_FEATURES).planLimits(PLAN_LIMITS);
    }

    static EntitlementGrant grant(String feature, String source) {
        return grant(feature, source, true, NOW.minus(Duration.ofDays(1)), null, null, null);
    }

    static EntitlementGrant grant(String feature, String source, boolean enabled, Instant startsAt, Instant endsAt, Instant revokedAt, Long limitValue) {
        return new EntitlementGrant(feature, source, enabled, startsAt, endsAt, revokedAt, limitValue);
    }

    static EffectiveEntitlements resolve(EntitlementInputs.Builder b) {
        return EntitlementResolver.resolveEffective(b.build());
    }

    @Nested
    class PlanFeatures {

        @Test
        void activeSubscriptionHasPlanFeatures() {
            EffectiveEntitlements result = resolve(inputs());
            assertThat(result.has("advanced_reports")).isTrue();
            assertThat(result.features()).isEqualTo(PLAN_FEATURES);
        }

        @ParameterizedTest
        @ValueSource(strings = {"trialing", "past_due"})
        void trialingAndPastDueKeepFeatures(String status) {
            assertThat(resolve(inputs().subscriptionStatus(status)).has("advanced_reports")).isTrue();
        }

        @Test
        void pastDueGraceDisabled() {
            EffectiveEntitlements result = resolve(inputs().subscriptionStatus("past_due").graceOnPastDue(false));
            assertThat(result.has("advanced_reports")).isFalse();
            assertThat(result.features()).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"canceled", "unpaid", "incomplete"})
        void deadStatusesLoseFeatures(String status) {
            EffectiveEntitlements result = resolve(inputs().subscriptionStatus(status));
            assertThat(result.features()).isEmpty();
            assertThat(result.limits()).isEmpty();
        }

        @Test
        void noSubscriptionNoFeatures() {
            assertThat(resolve(inputs().subscriptionStatus(null).planKey(null)).features()).isEmpty();
        }
    }

    @Nested
    class Grants {

        @Test
        void trialGrantAddsFeatureIndependentOfPlan() {
            EffectiveEntitlements result = resolve(inputs().subscriptionStatus("canceled")
                .grants(List.of(grant("advanced_reports", "trial", true, NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(14)), null, null))));
            assertThat(result.has("advanced_reports")).isTrue();
        }

        @Test
        void expiredGrantIgnored() {
            assertThat(resolve(inputs().grants(List.of(grant("beta_feature", "beta", true, NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)), null, null))))
                .has("beta_feature")).isFalse();
        }

        @Test
        void futureGrantIgnored() {
            assertThat(resolve(inputs().grants(List.of(grant("beta_feature", "beta", true, NOW.plus(Duration.ofDays(1)), null, null, null))))
                .has("beta_feature")).isFalse();
        }

        @Test
        void revokedGrantIgnored() {
            assertThat(resolve(inputs().grants(List.of(grant("beta_feature", "beta", true, NOW.minus(Duration.ofDays(1)), null, NOW.minus(Duration.ofHours(1)), null))))
                .has("beta_feature")).isFalse();
        }

        /** The kill switch: an override can REMOVE a plan feature. */
        @Test
        void disabledGrantKillsPlanFeature() {
            EffectiveEntitlements result = resolve(inputs().grants(List.of(grant("advanced_reports", "override", false, NOW.minus(Duration.ofDays(1)), null, null, null))));
            assertThat(result.has("advanced_reports")).isFalse();
            assertThat(result.has("basic_dashboard")).isTrue();
        }

        @Test
        void priorityEnterpriseBeatsOverride() {
            EffectiveEntitlements result = resolve(inputs().grants(List.of(
                grant("advanced_reports", "override", false, NOW.minus(Duration.ofDays(1)), null, null, null),
                grant("advanced_reports", "enterprise"))));
            assertThat(result.has("advanced_reports")).isTrue();
        }

        @Test
        void priorityHigherSourceWinsRegardlessOfOrder() {
            EffectiveEntitlements result = resolve(inputs().grants(List.of(
                grant("beta_feature", "enterprise", false, NOW.minus(Duration.ofDays(1)), null, null, null),
                grant("beta_feature", "promo"))));
            assertThat(result.has("beta_feature")).isFalse();
        }

        @Test
        void grantWorksWithNoPlanAtAll() {
            assertThat(resolve(inputs().subscriptionStatus(null).planKey(null).grants(List.of(grant("sso", "enterprise")))).has("sso")).isTrue();
        }
    }

    @Nested
    class Limits {

        @Test
        void planLimitsResolved() {
            EffectiveEntitlements result = resolve(inputs());
            assertThat(result.limitValue("users")).isEqualTo(10L);
            assertThat(result.limitValue("api_requests")).isEqualTo(100_000L);
            assertThat(result.limit("api_requests").softLimitRatio()).isEqualTo(0.8);
        }

        @Test
        void unlimited() {
            EffectiveEntitlements result = resolve(inputs());
            assertThat(result.limit("storage_bytes").isUnlimited()).isTrue();
            assertThat(result.withinLimit("storage_bytes", 1_000_000_000_000L)).isTrue();
        }

        @Test
        void withinLimitBoundary() {
            EffectiveEntitlements result = resolve(inputs());
            assertThat(result.withinLimit("users", 9)).isTrue();
            assertThat(result.withinLimit("users", 10)).isFalse();
        }

        @Test
        void unknownMetricIsUnlimited() {
            EffectiveEntitlements result = resolve(inputs());
            assertThat(result.limit("ai_tokens")).isNull();
            assertThat(result.withinLimit("ai_tokens", 1_000_000_000L)).isTrue();
        }

        @Test
        void limitAddonGrantRaisesCap() {
            EffectiveEntitlements result = resolve(inputs().grants(List.of(grant("limit:api_requests", "addon", true, NOW.minus(Duration.ofDays(1)), null, null, 500_000L))));
            assertThat(result.limitValue("api_requests")).isEqualTo(500_000L);
            assertThat(result.limit("api_requests").softLimitRatio()).isEqualTo(0.8); // preserved
        }

        @Test
        void limitGrantOnDeadSubscription() {
            EffectiveEntitlements result = resolve(inputs().subscriptionStatus("canceled")
                .grants(List.of(grant("limit:api_requests", "addon", true, NOW.minus(Duration.ofDays(1)), null, null, 50L))));
            assertThat(result.limitValue("api_requests")).isEqualTo(50L);
        }

        @Test
        void disabledLimitGrantIgnored() {
            EffectiveEntitlements result = resolve(inputs().grants(List.of(grant("limit:api_requests", "addon", false, NOW.minus(Duration.ofDays(1)), null, null, 500_000L))));
            assertThat(result.limitValue("api_requests")).isEqualTo(100_000L);
        }
    }

    @Nested
    class Metadata {

        @Test
        void carriesPlanAndStatus() {
            EffectiveEntitlements result = resolve(inputs());
            assertThat(result.planKey()).isEqualTo("pro");
            assertThat(result.subscriptionStatus()).isEqualTo("active");
        }
    }

    // ── Overage pricing rides on the limit ────────────────────────────────────────

    @Nested
    class OverageTests {

        @Test
        void billRoundsUpToWholeBlocksAndReconciles() {
            Overage overage = new Overage(1000, 20);
            assertThat(overage.bill(0)).isEqualTo(new Overage.Bill(0, 0));
            assertThat(overage.bill(1)).isEqualTo(new Overage.Bill(1, 20)); // a partial block is a whole block
            assertThat(overage.bill(4000)).isEqualTo(new Overage.Bill(4, 80));
            assertThat(overage.bill(4001)).isEqualTo(new Overage.Bill(5, 100));
            Overage.Bill bill = overage.bill(123_456);
            assertThat(bill.quantity() * overage.priceCents()).isEqualTo(bill.amountCents());
        }

        /** A limit:<metric> grant on a metric the plan never limited still bills overage. */
        @Test
        void addonLimitFallsBackToTheMetricDefaultPrice() {
            Instant now = Instant.parse("2026-09-28T00:00:00Z");
            EntitlementInputs in = EntitlementInputs.builder(UUID.randomUUID(), now).planKey("free").subscriptionStatus("active")
                .grants(List.of(grant("limit:ai_tokens", "addon", true, now, null, null, 1000L)))
                .metricOverage(Map.of("ai_tokens", new Overage(1000, 20)))
                .build();
            Limit limit = EntitlementResolver.resolveEffective(in).limit("ai_tokens");
            assertThat(limit).isNotNull();
            assertThat(limit.value()).isEqualTo(1000L);
            assertThat(limit.overage()).isEqualTo(new Overage(1000, 20));
        }

        @Test
        void planPriceBeatsTheMetricDefault() {
            Instant now = Instant.parse("2026-09-28T00:00:00Z");
            EntitlementInputs in = EntitlementInputs.builder(UUID.randomUUID(), now).planKey("pro").subscriptionStatus("active")
                .planLimits(Map.of("ai_tokens", new Limit(100L, null, new Overage(1000, 15))))
                .grants(List.of(grant("limit:ai_tokens", "addon", true, now, null, null, 5000L)))
                .metricOverage(Map.of("ai_tokens", new Overage(1000, 20)))
                .build();
            Limit limit = EntitlementResolver.resolveEffective(in).limit("ai_tokens");
            assertThat(limit).isNotNull();
            assertThat(limit.value()).isEqualTo(5000L); // the addon raised the cap
            assertThat(limit.overage()).isEqualTo(new Overage(1000, 15)); // the plan still prices overage
        }
    }
}
