package dev.synapse.entitlements;

import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.errors.EntitlementNotFoundError;
import dev.synapse.core.errors.FeatureNotEntitledError;
import dev.synapse.core.errors.PlanNotFoundError;
import dev.synapse.core.metrics.FrameworkMetrics;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import dev.synapse.entitlements.EntitlementResolver.EffectiveEntitlements;
import dev.synapse.entitlements.EntitlementResolver.EntitlementGrant;
import dev.synapse.entitlements.EntitlementResolver.EntitlementInputs;
import dev.synapse.entitlements.EntitlementResolver.Limit;
import dev.synapse.entitlements.EntitlementResolver.Overage;
import dev.synapse.subscriptions.Metric;
import dev.synapse.subscriptions.MetricRepository;
import dev.synapse.subscriptions.Plan;
import dev.synapse.subscriptions.PlanFeature;
import dev.synapse.subscriptions.PlanLimit;
import dev.synapse.subscriptions.PlanRepository;
import dev.synapse.subscriptions.Subscription;
import dev.synapse.subscriptions.SubscriptionService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles resolver inputs from the database, (optionally) caches, and
 * manages grants (reference: {@code entitlements/service.py}).
 */
@Service
public class EntitlementService {

    public static final String UPGRADE_URL = "/dashboard/billing";

    private final EntitlementRepository entitlements;
    private final SubscriptionService subscriptions;
    private final PlanRepository plans;
    private final MetricRepository metrics;
    private final EntitlementCache cache;
    private final OutboxWriter outbox;
    private final SynapseProperties props;
    private final FrameworkMetrics counters;

    public EntitlementService(EntitlementRepository entitlements, SubscriptionService subscriptions, PlanRepository plans, MetricRepository metrics,
                              EntitlementCache cache, OutboxWriter outbox, SynapseProperties props, FrameworkMetrics counters) {
        this.entitlements = entitlements;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.metrics = metrics;
        this.cache = cache;
        this.outbox = outbox;
        this.props = props;
        this.counters = counters;
    }

    // ── Resolution ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public EffectiveEntitlements effectiveForOrg(UUID organizationId) {
        Optional<EffectiveEntitlements> cached = cache.get(organizationId);
        if (cached.isPresent()) {
            return cached.get();
        }
        EffectiveEntitlements effective = compute(organizationId);
        cache.put(organizationId, effective);
        return effective;
    }

    /** 403 {@code feature_not_entitled} with upgrade hints unless the org has the feature. */
    @Transactional(readOnly = true)
    public EffectiveEntitlements requireFeature(UUID organizationId, String feature) {
        EffectiveEntitlements effective = effectiveForOrg(organizationId);
        if (!effective.has(feature)) {
            List<String> availableIn = plansWithFeature(feature);
            counters.featureGated(feature);
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("feature", feature);
            extras.put("current_plan", effective.planKey());
            extras.put("available_in", availableIn);
            extras.put("upgrade_url", UPGRADE_URL);
            throw new FeatureNotEntitledError("Feature '" + feature + "' is not available on the current plan", extras);
        }
        return effective;
    }

    @Transactional(readOnly = true)
    public List<String> plansWithFeature(String feature) {
        return plans.keysWithFeature(feature);
    }

    // ── Grant management (operator surface) ─────────────────────────────────────

    @Transactional
    public Entitlement grant(UUID organizationId, String featureKey, String source, Integer durationDays, boolean enabled, String note,
                             Long limitValue, UUID createdByUserId) {
        Instant now = Instant.now();
        Instant endsAt = durationDays != null && durationDays != 0 ? now.plus(Duration.ofDays(durationDays)) : null;
        Entitlement entitlement = entitlements.insert(organizationId, featureKey, source, enabled, now, endsAt, note, limitValue, createdByUserId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("feature_key", featureKey);
        payload.put("source", source);
        payload.put("ends_at", endsAt == null ? null : endsAt.toString());
        payload.put("limit_value", limitValue);
        outbox.append(Events.ENTITLEMENT_GRANTED, "entitlement", entitlement.id(), organizationId, payload);
        cache.invalidate(organizationId);
        return entitlement;
    }

    @Transactional(readOnly = true)
    public Entitlement get(UUID entitlementId) {
        return entitlements.findById(entitlementId)
            .orElseThrow(() -> new EntitlementNotFoundError("Grant not found", Map.of("entitlement_id", entitlementId.toString())));
    }

    @Transactional
    public Entitlement revoke(UUID entitlementId) {
        Entitlement entitlement = entitlements.findById(entitlementId).orElseThrow(() -> new EntitlementNotFoundError("Entitlement not found"));
        entitlements.revoke(entitlement.id(), Instant.now());
        outbox.append(Events.ENTITLEMENT_REVOKED, "entitlement", entitlement.id(), entitlement.organizationId(),
            Map.of("feature_key", entitlement.featureKey()));
        cache.invalidate(entitlement.organizationId());
        return entitlements.findById(entitlementId).orElseThrow();
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private EffectiveEntitlements compute(UUID organizationId) {
        Subscription subscription = subscriptions.currentForOrg(organizationId).orElse(null);
        Plan plan = null;
        if (subscription != null) {
            plan = subscriptions.planOf(subscription);
        } else {
            // No occupying subscription ⇒ default plan (free) so a fresh org resolves sensible features/limits.
            try {
                plan = subscriptions.planByKey(props.defaultPlanKey());
            } catch (PlanNotFoundError e) {
                plan = null; // no catalog seeded yet ⇒ no plan features/limits
            }
            // Any other failure propagates: an org must never silently resolve to "zero features".
        }

        List<EntitlementGrant> grants = entitlements.unrevokedForOrg(organizationId).stream().map(Entitlement::toGrant).toList();

        Set<String> planFeatures = plan == null ? Set.of()
            : plan.features().stream().map(PlanFeature::featureKey).collect(Collectors.toUnmodifiableSet());
        Map<String, Limit> planLimits = new LinkedHashMap<>();
        if (plan != null) {
            for (PlanLimit pl : plan.limits()) {
                Double soft = pl.softLimitRatio() != null && pl.softLimitRatio() != 0.0 ? pl.softLimitRatio() : null;
                Overage overage = pl.overageUnit() != null && pl.overagePriceCents() != null ? new Overage(pl.overageUnit(), pl.overagePriceCents()) : null;
                planLimits.put(pl.metric(), new Limit(pl.limitValue(), soft, overage));
            }
        }
        Map<String, Overage> metricOverage = new LinkedHashMap<>();
        for (Metric m : metrics.withOverage()) {
            metricOverage.put(m.key(), new Overage(m.overageUnit() == null ? 1 : m.overageUnit(), m.overagePriceCents() == null ? 0 : m.overagePriceCents()));
        }

        EntitlementInputs inputs = EntitlementInputs.builder(organizationId, Instant.now())
            .planKey(plan == null ? null : plan.key())
            .subscriptionStatus(subscription == null ? null : subscription.status())
            .planFeatures(planFeatures)
            .planLimits(planLimits)
            .grants(grants)
            .metricOverage(metricOverage)
            .graceOnPastDue(props.graceOnPastDue())
            .build();
        return EntitlementResolver.resolveEffective(inputs);
    }
}
