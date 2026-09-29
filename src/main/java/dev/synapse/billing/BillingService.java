package dev.synapse.billing;

import dev.synapse.billing.BillingRefs.BillingCustomerRef;
import dev.synapse.billing.BillingRefs.ChangePlanRequest;
import dev.synapse.billing.BillingRefs.CheckoutResult;
import dev.synapse.billing.BillingRefs.CreateCheckoutRequest;
import dev.synapse.billing.BillingRefs.CreateCustomerRequest;
import dev.synapse.billing.BillingRefs.SubscriptionRef;
import dev.synapse.billing.invoicing.InvoiceRepository;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.errors.CheckoutConfirmNotAllowedError;
import dev.synapse.core.errors.CheckoutRequiredError;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import dev.synapse.identity.User;
import dev.synapse.identity.UserRepository;
import dev.synapse.subscriptions.Plan;
import dev.synapse.subscriptions.Proration;
import dev.synapse.subscriptions.Subscription;
import dev.synapse.subscriptions.SubscriptionService;
import dev.synapse.tenancy.Organization;
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
 * Billing domain service (reference: {@code billing/service.py}): customers,
 * checkout, the billing portal, and plan changes through the active provider.
 * Provider calls happen BEFORE the database mutation so a failed provider call
 * leaves no local state behind.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    public static final String UPGRADE_URL = "/dashboard/billing";
    public static final String CHECKOUT_URL = "/v1/billing/checkout";
    /** The tenant confirming its own activation — only trustworthy on a {@code client_confirm} provider. */
    public static final String SOURCE_CLIENT_CONFIRM = "client_confirm";
    public static final String SOURCE_WEBHOOK = "webhook";

    /** What the org was paying, and for which period, before the change. */
    public record PeriodSnapshot(String planKey, long priceCents, Instant periodStart, Instant periodEnd) {}

    private final SubscriptionService subscriptions;
    private final BillingProviderRegistry providers;
    private final BillingCustomerRepository customers;
    private final UserRepository users;
    private final InvoiceRepository invoices;
    private final OutboxWriter outbox;
    private final SynapseProperties props;

    public BillingService(SubscriptionService subscriptions, BillingProviderRegistry providers, BillingCustomerRepository customers,
                          UserRepository users, InvoiceRepository invoices, OutboxWriter outbox, SynapseProperties props) {
        this.subscriptions = subscriptions;
        this.providers = providers;
        this.customers = customers;
        this.users = users;
        this.invoices = invoices;
        this.outbox = outbox;
        this.props = props;
    }

    // ── Customers ─────────────────────────────────────────────────────────────────

    /** The org's billing customer, creating the provider-side object on first use. */
    @Transactional
    public BillingCustomer ensureCustomer(Organization organization, UUID contactUserId, BillingProvider provider) {
        BillingCustomer existing = customers.findByOrg(organization.id()).orElse(null);
        if (existing != null) {
            return existing;
        }
        User contact = contactUserId != null ? users.findById(contactUserId).orElse(null)
            : organization.ownerUserId() == null ? null : users.findById(organization.ownerUserId()).orElse(null);
        String email = contact != null ? contact.email() : organization.slug() + "@example.com";
        String name = contact != null ? contact.displayName() : organization.name();
        BillingCustomerRef ref = provider.createCustomer(
            new CreateCustomerRequest(email, name, organization.id(), props.billingCurrency()));
        return customers.insert(organization.id(), provider.name(), ref.providerCustomerId(), ref.email(), ref.name(),
            props.billingCurrency());
    }

    // ── Checkout ──────────────────────────────────────────────────────────────────

    /** Create a checkout with the provider and return its result (a URL, or manual instructions). */
    @Transactional
    public CheckoutResult startCheckout(Organization organization, Plan plan, String successUrl, String cancelUrl, UUID contactUserId) {
        BillingProvider provider = providers.current();
        BillingCustomer customer = ensureCustomer(organization, contactUserId, provider);
        return provider.createCheckout(new CreateCheckoutRequest(plan.key(), plan.name(), plan.priceCents() == null ? 0 : plan.priceCents(),
            plan.currency(), plan.interval() == null ? "month" : plan.interval(), customer.providerCustomerId(), successUrl, cancelUrl,
            organization.id()));
    }

    /**
     * Activate the subscription after checkout.
     *
     * <p>{@code source = webhook} is the provider telling us payment happened;
     * {@code source = client_confirm} is the tenant telling us — trustworthy only
     * when the provider has no payment truth of its own
     * ({@link BillingCapability#CLIENT_CONFIRM}), else 409.
     */
    @Transactional
    public Subscription completeCheckout(Organization organization, Plan plan, String providerSubscriptionId, UUID contactUserId, String source) {
        BillingProvider provider = providers.current();
        if (SOURCE_CLIENT_CONFIRM.equals(source) && !provider.supports().contains(BillingCapability.CLIENT_CONFIRM)) {
            throw new CheckoutConfirmNotAllowedError(provider.name() + " verifies payment via its own callback; activation "
                + "happens when the provider webhook arrives, not on client confirmation", Map.of("provider", provider.name()));
        }
        BillingCustomer customer = ensureCustomer(organization, contactUserId, provider);
        Subscription subscription = subscriptions.changePlan(organization.id(), plan.key(), provider.name(), providerSubscriptionId, false);
        recordInvoice(customer, plan, provider);
        return subscription;
    }

    /**
     * The charge the checkout just settled, recorded as an OPEN provider invoice
     * (reference: {@code BillingService._record_invoice}). It carries no lines —
     * a provider's invoice shape is the provider's; the framework's own drafts
     * come from {@code InvoicingService}. A free plan records nothing.
     */
    private void recordInvoice(BillingCustomer customer, Plan plan, BillingProvider provider) {
        long priceCents = plan.priceCents() == null ? 0 : plan.priceCents();
        if (priceCents == 0) {
            return;
        }
        UUID invoiceId = invoices.insert(customer.organizationId(), customer.id(), provider.name(), plan.currency(),
            priceCents, 0, priceCents, "open", null, null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total_cents", priceCents);
        payload.put("currency", plan.currency());
        payload.put("plan_key", plan.key());
        outbox.append(Events.INVOICE_CREATED, "invoice", invoiceId, customer.organizationId(), payload);
    }

    /** {@code null} when the provider has no portal, or the org has no provider customer yet. */
    @Transactional
    public String billingPortalUrl(Organization organization, String returnUrl) {
        BillingProvider provider = providers.current();
        if (!provider.supports().contains(BillingCapability.BILLING_PORTAL)) {
            return null;
        }
        BillingCustomer customer = ensureCustomer(organization, null, provider);
        if (customer.providerCustomerId() == null || customer.providerCustomerId().isBlank()) {
            return null;
        }
        return provider.billingPortalUrl(customer.providerCustomerId(), returnUrl);
    }

    // ── Plan change ───────────────────────────────────────────────────────────────

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
            // The provider call happens BEFORE the local mutation so a failed call leaves no state behind.
            SubscriptionRef ref = provider.changePlan(current.providerSubscriptionId(),
                new ChangePlanRequest(plan.key(), plan.priceCents() == null ? 0 : plan.priceCents(), plan.currency(),
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
