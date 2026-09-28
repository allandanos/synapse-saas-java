package dev.synapse.entitlements;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Entitlement resolution — the heart of pricing-as-config (reference: {@code entitlements/resolver.py}).
 *
 * <p>Pure function: rows in, effective feature/limit set out. Rules:
 * <ol>
 *   <li>Plan features apply iff the subscription status is trialing/active (past_due
 *       while {@code graceOnPastDue}). No occupying subscription ⇒ the caller passes the default plan.</li>
 *   <li>Grants apply when un-revoked and inside their time window.</li>
 *   <li>Conflicts resolve by source priority: plan=0 &lt; addon=10 &lt; beta=20 &lt; promo=30 &lt;
 *       grandfather=40 &lt; override=50 &lt; enterprise=60. A winning grant with
 *       {@code enabled=false} REMOVES the feature (kill switch).</li>
 *   <li>Limits merge plan limits overridden per metric by grants of the synthetic
 *       feature key {@code limit:<metric>}.</li>
 * </ol>
 */
public final class EntitlementResolver {

    private EntitlementResolver() {}

    public static final String LIMIT_FEATURE_PREFIX = "limit:";
    public static final String PLAN_SOURCE = "plan";
    public static final List<String> ENTITLEMENT_SOURCES = List.of("trial", "addon", "promo", "beta", "override", "enterprise", "grandfather");

    /** Higher wins on conflict; a winning enabled=false grant removes a plan feature. */
    public static final Map<String, Integer> SOURCE_PRIORITY = Map.of(
        "plan", 0, "addon", 10, "beta", 20, "promo", 30, "grandfather", 40, "override", 50, "enterprise", 60);

    /** Projection of an entitlements-table row. {@code limitValue} is only meaningful for {@code limit:<metric>} grants. */
    public record EntitlementGrant(String featureKey, String source, boolean enabled, Instant startsAt, Instant endsAt, Instant revokedAt, Long limitValue) {

        boolean isActiveAt(Instant now) {
            if (revokedAt != null) {
                return false;
            }
            if (startsAt.isAfter(now)) {
                return false;
            }
            return endsAt == null || now.isBefore(endsAt);
        }
    }

    /** Billing for usage past the limit: {@code priceCents} per {@code unit} units, rounded up. */
    public record Overage(int unit, long priceCents) {

        public record Bill(long quantity, long amountCents) {}

        /** (billable quantity in {@code unit} blocks, amount in cents) — reconciles as qty × price. */
        public Bill bill(long unitsOver) {
            if (unitsOver <= 0) {
                return new Bill(0, 0);
            }
            long quantity = Math.floorDiv(-unitsOver, unit) * -1; // ceil
            return new Bill(quantity, quantity * priceCents);
        }
    }

    /** {@code value == null} ⇒ unlimited; {@code overage == null} ⇒ past-limit usage is never billed. */
    public record Limit(Long value, Double softLimitRatio, Overage overage) {

        public static Limit of(Long value, Double softLimitRatio) {
            return new Limit(value, softLimitRatio, null);
        }

        public boolean isUnlimited() {
            return value == null;
        }
    }

    public record EntitlementInputs(UUID organizationId, Instant now, String planKey, String subscriptionStatus, Set<String> planFeatures,
                                    Map<String, Limit> planLimits, List<EntitlementGrant> grants, Map<String, Overage> metricOverage,
                                    boolean graceOnPastDue) {

        public EntitlementInputs {
            planFeatures = Set.copyOf(planFeatures);
            planLimits = Map.copyOf(planLimits);
            grants = List.copyOf(grants);
            metricOverage = Map.copyOf(metricOverage);
        }

        public static Builder builder(UUID organizationId, Instant now) {
            return new Builder(organizationId, now);
        }

        /** Named-argument construction (the reference's keyword defaults). */
        public static final class Builder {
            private final UUID organizationId;
            private final Instant now;
            private String planKey;
            private String subscriptionStatus;
            private Set<String> planFeatures = Set.of();
            private Map<String, Limit> planLimits = Map.of();
            private List<EntitlementGrant> grants = List.of();
            private Map<String, Overage> metricOverage = Map.of();
            private boolean graceOnPastDue = true;

            private Builder(UUID organizationId, Instant now) {
                this.organizationId = organizationId;
                this.now = now;
            }

            public Builder planKey(String value) { this.planKey = value; return this; }
            public Builder subscriptionStatus(String value) { this.subscriptionStatus = value; return this; }
            public Builder planFeatures(Set<String> value) { this.planFeatures = value; return this; }
            public Builder planLimits(Map<String, Limit> value) { this.planLimits = value; return this; }
            public Builder grants(List<EntitlementGrant> value) { this.grants = value; return this; }
            public Builder metricOverage(Map<String, Overage> value) { this.metricOverage = value; return this; }
            public Builder graceOnPastDue(boolean value) { this.graceOnPastDue = value; return this; }

            public EntitlementInputs build() {
                return new EntitlementInputs(organizationId, now, planKey, subscriptionStatus, planFeatures, planLimits, grants, metricOverage, graceOnPastDue);
            }
        }
    }

    public record EffectiveEntitlements(UUID organizationId, String planKey, String subscriptionStatus, Set<String> features, Map<String, Limit> limits) {

        public EffectiveEntitlements {
            features = Set.copyOf(features);
            limits = Map.copyOf(limits);
        }

        public boolean has(String feature) {
            return features.contains(feature);
        }

        /** The limit for a metric, or null when nothing caps it. */
        public Limit limit(String metric) {
            return limits.get(metric);
        }

        public Long limitValue(String metric) {
            Limit lim = limits.get(metric);
            return lim == null ? null : lim.value();
        }

        public boolean withinLimit(String metric, long used) {
            Limit lim = limits.get(metric);
            if (lim == null || lim.value() == null) {
                return true;
            }
            return used < lim.value();
        }

        public List<String> sortedFeatures() {
            return features.stream().sorted().toList();
        }
    }

    public static EffectiveEntitlements resolveEffective(EntitlementInputs inputs) {
        // ── 1. plan features in effect? ──────────────────────────────────────────
        String status = inputs.subscriptionStatus();
        boolean planActive = status != null
            && ("trialing".equals(status) || "active".equals(status) || (inputs.graceOnPastDue() && "past_due".equals(status)));

        // ── 2. active grants ──────────────────────────────────────────────────────
        List<EntitlementGrant> activeGrants = inputs.grants().stream().filter(g -> g.isActiveAt(inputs.now())).toList();

        // ── 3. features: plan set, then grant overlay by priority ─────────────────
        record Decision(int priority, boolean enabled) {}
        Map<String, Decision> decisions = new LinkedHashMap<>();
        if (planActive) {
            for (String feature : sorted(inputs.planFeatures())) {
                decisions.put(feature, new Decision(SOURCE_PRIORITY.get(PLAN_SOURCE), true));
            }
        }
        for (EntitlementGrant grant : activeGrants) {
            if (grant.featureKey().startsWith(LIMIT_FEATURE_PREFIX)) {
                continue; // handled in the limit pass
            }
            int priority = SOURCE_PRIORITY.getOrDefault(grant.source(), 0);
            Decision current = decisions.get(grant.featureKey());
            if (current == null || priority > current.priority()) {
                decisions.put(grant.featureKey(), new Decision(priority, grant.enabled()));
            }
        }
        Set<String> features = decisions.entrySet().stream().filter(e -> e.getValue().enabled()).map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());

        // ── 4. limits: plan limits, then `limit:<metric>` grants override ─────────
        Map<String, Limit> limits = planActive ? new LinkedHashMap<>(inputs.planLimits()) : new LinkedHashMap<>();
        for (EntitlementGrant grant : activeGrants) {
            if (!grant.featureKey().startsWith(LIMIT_FEATURE_PREFIX) || !grant.enabled()) {
                continue;
            }
            String metric = grant.featureKey().substring(LIMIT_FEATURE_PREFIX.length());
            Limit base = limits.getOrDefault(metric, Limit.of(null, null));
            limits.put(metric, new Limit(
                grant.limitValue() != null ? grant.limitValue() : base.value(),
                base.softLimitRatio(),
                // an addon raises the cap; the plan (else the metric) still prices overage
                base.overage() != null ? base.overage() : inputs.metricOverage().get(metric)));
        }

        return new EffectiveEntitlements(inputs.organizationId(), inputs.planKey(), inputs.subscriptionStatus(), features, limits);
    }

    private static List<String> sorted(Collection<String> values) {
        return values.stream().sorted().toList();
    }
}
