package dev.synapse.billing;

import dev.synapse.core.errors.CheckoutRequiredError;
import dev.synapse.subscriptions.Plan;
import dev.synapse.subscriptions.Proration;
import dev.synapse.subscriptions.Subscription;
import dev.synapse.subscriptions.SubscriptionService;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan changes through the active provider (reference: {@code billing/service.py:change_plan}).
 * Customers, checkout, invoices and webhooks join in milestone 4.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    public static final String UPGRADE_URL = "/dashboard/billing";
    public static final String CHECKOUT_URL = "/v1/billing/checkout";

    /** What the org was paying, and for which period, before the change. */
    public record PeriodSnapshot(String planKey, long priceCents, Instant periodStart, Instant periodEnd) {}

    private final SubscriptionService subscriptions;
    private final BillingProviderRegistry providers;

    public BillingService(SubscriptionService subscriptions, BillingProviderRegistry providers) {
        this.subscriptions = subscriptions;
        this.providers = providers;
    }

    /**
     * Change the org's plan — through the provider when the provider bills.
     * <ul>
     *   <li>provider bills recurring (Stripe/…): the subscription must have been
     *       purchased through it, else 409 {@code checkout_required}; the provider
     *       owns proration and invoicing.</li>
     *   <li>otherwise (manual/Xendit/PayMongo/Paddle): apply locally. paid→paid keeps
     *       the period and queues the prorated correction for that period's
     *       invoice (billed in arrears); free→paid starts a fresh cycle today.</li>
     * </ul>
     */
    @Transactional
    public Subscription changePlan(UUID organizationId, String planKey) {
        BillingProvider provider = providers.current();
        Plan plan = subscriptions.planByKey(planKey);
        Subscription current = subscriptions.currentForOrg(organizationId).orElse(null);

        if (provider.supports().contains(BillingCapability.RECURRING_HOSTED)) {
            if (current == null || current.providerSubscriptionId() == null || current.providerSubscriptionId().isBlank()) {
                throw new CheckoutRequiredError("Plan changes on " + provider.name() + " require a subscription purchased through it",
                    Map.of("plan_key", planKey, "checkout_url", CHECKOUT_URL));
            }
            // ── MILESTONE 4 SEAM: the provider call happens BEFORE the local mutation so a failed call leaves no state behind.
            BillingProvider.SubscriptionRef ref = provider.changePlan(current.providerSubscriptionId(),
                new BillingProvider.ChangePlanRequest(plan.key(), plan.priceCents() == null ? 0 : plan.priceCents(), plan.currency(),
                    plan.interval() == null ? "month" : plan.interval()));
            return subscriptions.changePlan(organizationId, plan.key(), provider.name(), ref.providerSubscriptionId(), true);
        }

        PeriodSnapshot previous = periodSnapshot(current);
        // A paid→paid switch keeps the billing period and prorates; a free→paid
        // upgrade starts a fresh cycle today (nothing to prorate on ₱0).
        boolean keepPeriod = previous != null && previous.priceCents() > 0;
        Subscription subscription = subscriptions.changePlan(organizationId, plan.key(), provider.name(), null, keepPeriod);
        Map<String, Object> adjustment = keepPeriod ? prorationAdjustment(previous, subscription, plan) : null;
        if (adjustment != null) {
            subscription = subscriptions.queueAdjustment(subscription, adjustment);
            log.info("plan_change_prorated org={} net_cents={} from_plan={} to_plan={}", organizationId,
                adjustment.get("amount_cents"), adjustment.get("from_plan"), adjustment.get("to_plan"));
        }
        return subscription;
    }

    static PeriodSnapshot periodSnapshot(Subscription subscription) {
        if (subscription == null || !"active".equals(subscription.status())) {
            return null;
        }
        Object key = subscription.planSnapshot().get("key");
        Object price = subscription.planSnapshot().get("price_cents");
        return new PeriodSnapshot(key == null ? "" : String.valueOf(key), price instanceof Number n ? n.longValue() : 0L,
            subscription.currentPeriodStart(), subscription.currentPeriodEnd());
    }

    /** Prorate the switch if the period was kept; null when nothing is owed either way. */
    static Map<String, Object> prorationAdjustment(PeriodSnapshot previous, Subscription subscription, Plan plan) {
        if (previous == null || !subscription.currentPeriodEnd().equals(previous.periodEnd())) {
            return null; // period reset (trial/lapsed) ⇒ the new period bills in full
        }
        Instant now = Instant.now();
        long newPrice = plan.priceCents() == null ? 0 : plan.priceCents();
        long amount = Proration.arrearsAdjustmentCents(previous.priceCents(), newPrice, previous.periodStart(), previous.periodEnd(), now);
        if (amount == 0) {
            return null;
        }
        String elapsed = Proration.prorate(previous.priceCents(), newPrice, previous.periodStart(), previous.periodEnd(), now)
            .elapsedFraction().movePointRight(2).setScale(1, RoundingMode.HALF_EVEN).toPlainString() + "%";
        String what = amount < 0 ? "credit" : "charge";
        Map<String, Object> adjustment = new LinkedHashMap<>();
        adjustment.put("kind", "proration");
        adjustment.put("amount_cents", amount);
        adjustment.put("description", "Plan change " + previous.planKey() + " → " + plan.key() + ": " + what + " for " + elapsed
            + " of the period at the previous price");
        adjustment.put("from_plan", previous.planKey());
        adjustment.put("to_plan", plan.key());
        adjustment.put("from_price_cents", previous.priceCents());
        adjustment.put("to_price_cents", newPrice);
        adjustment.put("created_at", now.toString());
        return adjustment;
    }
}
