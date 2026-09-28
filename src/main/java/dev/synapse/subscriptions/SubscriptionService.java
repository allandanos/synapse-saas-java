package dev.synapse.subscriptions;

import dev.synapse.core.audit.AuditService;
import dev.synapse.core.errors.PlanNotFoundError;
import dev.synapse.core.errors.SubscriptionNotFoundError;
import dev.synapse.core.errors.TrialNotAllowedError;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import dev.synapse.entitlements.EntitlementCache;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subscription lifecycle (reference: {@code subscriptions/service.py}): create,
 * trial, plan change, cancel/resume. Plan changes capture a {@code plan_snapshot}
 * (grandfathering) and always invalidate the org's entitlements.
 */
@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final EntitlementCache cache;

    public SubscriptionService(SubscriptionRepository subscriptions, PlanRepository plans, AuditService audit, OutboxWriter outbox,
                               EntitlementCache cache) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.audit = audit;
        this.outbox = outbox;
        this.cache = cache;
    }

    // ── Queries ──────────────────────────────────────────────────────────────────

    /** The occupying subscription (trialing/active/past_due), if any. */
    @Transactional(readOnly = true)
    public Optional<Subscription> currentForOrg(UUID organizationId) {
        return subscriptions.currentForOrg(organizationId);
    }

    @Transactional(readOnly = true)
    public Subscription getOr404(UUID subscriptionId) {
        return subscriptions.findById(subscriptionId).orElseThrow(() -> new SubscriptionNotFoundError("Subscription not found"));
    }

    @Transactional(readOnly = true)
    public Plan planByKey(String key) {
        return planByKey(key, false);
    }

    /** Plan with features/limits attached (snapshots and responses need them). */
    @Transactional(readOnly = true)
    public Plan planByKey(String key, boolean includeArchived) {
        return plans.findByKey(key, includeArchived).orElseThrow(() -> new PlanNotFoundError("Plan '" + key + "' not found"));
    }

    /** The plan a subscription points at (responses nest it). */
    @Transactional(readOnly = true)
    public Plan planOf(Subscription subscription) {
        return plans.findById(subscription.planId()).orElseThrow(() -> new PlanNotFoundError("Plan not found"));
    }

    // ── Commands ─────────────────────────────────────────────────────────────────

    @Transactional
    public Subscription createSubscription(UUID organizationId, Plan plan, String status, Instant currentPeriodStart, Instant currentPeriodEnd,
                                           Instant trialEndsAt, String provider, String providerSubscriptionId, UUID billingCustomerId) {
        Instant now = Instant.now();
        Instant start = currentPeriodStart != null ? currentPeriodStart : now;
        Instant end = currentPeriodEnd != null ? currentPeriodEnd : start.plus(plan.intervalLength());
        Subscription subscription = subscriptions.insert(organizationId, plan.id(), status, start, end, trialEndsAt, provider,
            providerSubscriptionId, billingCustomerId, snapshot(plan));
        String event = "trialing".equals(status) ? Events.SUBSCRIPTION_TRIAL_STARTED : Events.SUBSCRIPTION_ACTIVATED;
        emit(event, subscription, plan, Map.of("organization_id", organizationId.toString()));
        cache.invalidate(organizationId);
        return subscription;
    }

    /** Replace the occupying subscription with a trialing one on {@code planKey}. */
    @Transactional
    public Subscription startTrial(UUID organizationId, String planKey, Integer trialDays) {
        Plan plan = planByKey(planKey);
        if (plan.trialDays() == 0 && trialDays == null) {
            throw new TrialNotAllowedError("Plan '" + planKey + "' has no trial period");
        }
        Subscription existing = subscriptions.currentForOrg(organizationId).orElse(null);
        if (existing != null && "trialing".equals(existing.status())) {
            throw new TrialNotAllowedError("A trial is already in progress for this organization");
        }
        int days = trialDays != null ? trialDays : plan.trialDays();
        Instant now = Instant.now();
        Instant trialEnd = now.plus(Duration.ofDays(days));

        if (existing != null) {
            // End the current subscription, then trial on top
            SubscriptionStateMachine.assertTransition(existing.status(), "canceled");
            subscriptions.save(existing.withStatus("canceled").withCancellation(existing.cancelAtPeriodEnd(), now));
        }
        Subscription subscription = createSubscription(organizationId, plan, "trialing", now, trialEnd, trialEnd, null, null, null);
        audit.log(Events.SUBSCRIPTION_TRIAL_STARTED, organizationId, null, "subscription", subscription.id(),
            Map.of("plan", planKey, "trial_days", days));
        return subscription;
    }

    /**
     * Switch the occupying subscription to a new plan immediately.
     * {@code keepPeriod} (mid-period change): the current billing period is kept
     * so the caller can prorate the difference; the period only resets when
     * there is no live period to keep (trial, lapsed, new).
     */
    @Transactional
    public Subscription changePlan(UUID organizationId, String planKey, String provider, String providerSubscriptionId, boolean keepPeriod) {
        Plan plan = planByKey(planKey);
        Subscription existing = subscriptions.currentForOrg(organizationId).orElse(null);
        Instant now = Instant.now();

        if (existing == null) {
            return createSubscription(organizationId, plan, "active", null, null, null, provider, providerSubscriptionId, null);
        }

        String fromSnapshot = existing.snapshotKey();
        Subscription updated = existing.withStatus("active").withPlan(plan.id(), snapshot(plan));
        boolean periodIsLive = updated.currentPeriodEnd().isAfter(now);
        if (!(keepPeriod && periodIsLive)) {
            updated = updated.withPeriod(now, now.plus(plan.intervalLength()));
        }
        updated = updated.withCancellation(false, null);
        if (provider != null || providerSubscriptionId != null) {
            updated = updated.withProvider(provider != null ? provider : updated.provider(),
                providerSubscriptionId != null ? providerSubscriptionId : updated.providerSubscriptionId());
        }
        Subscription saved = subscriptions.save(updated);

        emit(Events.SUBSCRIPTION_PLAN_CHANGED, saved, plan, Map.of("from_plan", String.valueOf(fromSnapshot), "to_plan", plan.key()));
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("from", fromSnapshot);
        diff.put("to", plan.key());
        audit.log(Events.SUBSCRIPTION_PLAN_CHANGED, organizationId, null, "subscription", saved.id(), diff);
        cache.invalidate(organizationId);
        return saved;
    }

    @Transactional
    public Subscription cancel(UUID organizationId, boolean atPeriodEnd) {
        Subscription subscription = requireCurrent(organizationId);
        Instant now = Instant.now();
        SubscriptionStateMachine.assertTransition(subscription.status(), "canceled");
        Subscription updated = atPeriodEnd
            ? subscription.withCancellation(true, subscription.canceledAt())
            : subscription.withStatus("canceled").withCancellation(subscription.cancelAtPeriodEnd(), now);
        Subscription saved = subscriptions.save(updated);
        emit(Events.SUBSCRIPTION_CANCELED, saved, planOf(saved), Map.of());
        audit.log(Events.SUBSCRIPTION_CANCELED, organizationId, null, "subscription", saved.id(), Map.of("at_period_end", atPeriodEnd));
        cache.invalidate(organizationId);
        return saved;
    }

    @Transactional
    public Subscription resume(UUID organizationId) {
        Subscription subscription = requireCurrent(organizationId);
        if (subscription.cancelAtPeriodEnd()) {
            Subscription saved = subscriptions.save(subscription.withCancellation(false, subscription.canceledAt()));
            emit(Events.SUBSCRIPTION_RESUMED, saved, planOf(saved), Map.of());
            cache.invalidate(organizationId);
            return saved;
        }
        throw new SubscriptionNotFoundError("Subscription is not scheduled for cancellation");
    }

    /** Webhook-driven status change (milestone 4 callers); idempotent and transition-checked. */
    @Transactional
    public Subscription applyProviderTransition(Subscription subscription, String targetStatus, Instant currentPeriodEnd) {
        SubscriptionStateMachine.assertTransition(subscription.status(), targetStatus);
        Subscription updated = subscription.withStatus(targetStatus);
        if (currentPeriodEnd != null) {
            updated = updated.withPeriodEnd(currentPeriodEnd);
        }
        Subscription saved = subscriptions.save(updated);
        cache.invalidate(subscription.organizationId());
        return saved;
    }

    /** Queue a prorated credit/charge for the next period invoice ({@code pending_adjustments}). */
    @Transactional
    public Subscription queueAdjustment(Subscription subscription, Map<String, Object> adjustment) {
        List<Map<String, Object>> adjustments = new ArrayList<>(subscription.pendingAdjustments());
        adjustments.add(adjustment);
        return subscriptions.save(subscription.withPendingAdjustments(adjustments));
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private Subscription requireCurrent(UUID organizationId) {
        return subscriptions.currentForOrg(organizationId)
            .orElseThrow(() -> new SubscriptionNotFoundError("No active subscription for this organization"));
    }

    private void emit(String eventType, Subscription subscription, Plan plan, Map<String, String> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subscription_id", subscription.id().toString());
        payload.put("organization_id", subscription.organizationId().toString());
        payload.put("plan_key", plan.key());
        payload.put("status", subscription.status());
        payload.putAll(extra);
        outbox.append(eventType, "subscription", subscription.id(), subscription.organizationId(), payload);
    }

    /** Freeze purchase-time pricing/features so later YAML edits never rewrite history. */
    public static Map<String, Object> snapshot(Plan plan) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("key", plan.key());
        snapshot.put("name", plan.name());
        snapshot.put("price_cents", plan.priceCents());
        snapshot.put("currency", plan.currency());
        snapshot.put("interval", plan.interval());
        snapshot.put("features", plan.features().stream().filter(PlanFeature::enabled).map(PlanFeature::featureKey).toList());
        Map<String, Object> limits = new LinkedHashMap<>();
        Map<String, Object> overage = new LinkedHashMap<>();
        for (PlanLimit pl : plan.limits()) {
            limits.put(pl.metric(), pl.limitValue());
            if (pl.overageUnit() != null && pl.overagePriceCents() != null) {
                Map<String, Object> price = new LinkedHashMap<>();
                price.put("unit", pl.overageUnit());
                price.put("price_cents", pl.overagePriceCents());
                overage.put(pl.metric(), price);
            }
        }
        snapshot.put("limits", limits);
        snapshot.put("overage", overage);
        return snapshot;
    }
}
