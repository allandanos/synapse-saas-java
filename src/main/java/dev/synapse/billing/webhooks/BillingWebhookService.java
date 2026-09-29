package dev.synapse.billing.webhooks;

import dev.synapse.billing.BillingCustomer;
import dev.synapse.billing.BillingCustomerRepository;
import dev.synapse.billing.BillingProvider;
import dev.synapse.billing.BillingProviderRegistry;
import dev.synapse.billing.BillingService;
import dev.synapse.billing.NormalizedBillingEvent;
import dev.synapse.billing.VerifiedWebhook;
import dev.synapse.billing.WebhookRequest;
import dev.synapse.billing.invoicing.Invoice;
import dev.synapse.billing.invoicing.InvoiceRepository;
import dev.synapse.core.db.RlsGucs;
import dev.synapse.core.errors.DomainError;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import dev.synapse.subscriptions.Plan;
import dev.synapse.subscriptions.Subscription;
import dev.synapse.subscriptions.SubscriptionService;
import dev.synapse.tenancy.Organization;
import dev.synapse.tenancy.OrganizationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Billing webhook ingest (reference: {@code billing/webhooks.py}). Per event:
 * <ol>
 *   <li>verify the signature over the raw bytes (provider-specific);</li>
 *   <li>insert into {@code provider_webhook_events} — a duplicate is a 200 no-op;</li>
 *   <li>translate to canonical events;</li>
 *   <li>apply each idempotently (upserts keyed on provider ids + the state machine).</li>
 * </ol>
 *
 * <p>Failure semantics: a business rejection ({@link DomainError} — e.g. an
 * illegal transition from a late event) is recorded on the ledger row and the
 * request still answers 200, because retrying cannot change the outcome. Any
 * other failure propagates: the ledger row rolls back with the transaction and
 * the provider's retry re-processes the event, so a transient database error
 * can no longer permanently lose {@code invoice.paid} or
 * {@code subscription.canceled}.
 */
@Service
public class BillingWebhookService {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookService.class);

    private final BillingProviderRegistry providers;
    private final ProviderWebhookEventRepository ledger;
    private final SubscriptionService subscriptions;
    private final OrganizationService organizations;
    private final BillingService billing;
    private final BillingCustomerRepository customers;
    private final InvoiceRepository invoices;
    private final OutboxWriter outbox;
    private final RlsGucs rls;
    private final JdbcClient jdbc;
    private final TransactionTemplate savepoint;

    public BillingWebhookService(BillingProviderRegistry providers, ProviderWebhookEventRepository ledger, SubscriptionService subscriptions,
                                 OrganizationService organizations, BillingService billing, BillingCustomerRepository customers,
                                 InvoiceRepository invoices, OutboxWriter outbox, RlsGucs rls, JdbcClient jdbc,
                                 PlatformTransactionManager transactions) {
        this.providers = providers;
        this.ledger = ledger;
        this.subscriptions = subscriptions;
        this.organizations = organizations;
        this.billing = billing;
        this.customers = customers;
        this.invoices = invoices;
        this.outbox = outbox;
        this.rls = rls;
        this.jdbc = jdbc;
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Transactional
    public Map<String, Object> handle(String providerName, WebhookRequest raw) {
        BillingProvider provider = providers.byName(providerName);
        VerifiedWebhook verified = provider.verifyWebhook(raw);

        UUID ledgerId = ledger.claim(providerName, verified.providerEventId(), verified.eventType()).orElse(null);
        if (ledgerId == null) {
            log.info("webhook_duplicate_ignored provider={} provider_event={}", providerName, verified.providerEventId());
            return result("duplicate", 0, null);
        }

        List<NormalizedBillingEvent> normalized;
        try {
            normalized = provider.translateWebhook(verified);
        } catch (RuntimeException e) {
            // A payload we cannot translate will not translate on retry either:
            // record it on the ledger row and answer 200 so the provider stops.
            log.warn("webhook_translate_failed provider={} error={}", providerName, e.toString());
            ledger.markProcessed(ledgerId, Instant.now(), "translate: " + e.getMessage());
            return result("unprocessable", 0, 1);
        }

        int applied = 0;
        List<String> rejected = new ArrayList<>();
        for (NormalizedBillingEvent event : normalized) {
            try {
                savepoint.executeWithoutResult(status -> apply(providerName, event));
                applied++;
            } catch (DomainError e) {
                // Business rejection (illegal transition, validation): deterministic,
                // so retrying cannot help — record it and move on.
                rejected.add(event.eventType() + ": " + e.getMessage());
                log.info("webhook_event_rejected provider={} event_type={} error={}", providerName, event.eventType(), e.getMessage());
            }
            // Anything else (database/infra) propagates: the request 500s, the ledger
            // row rolls back with it, and the provider's retry re-processes.
        }
        ledger.markProcessed(ledgerId, Instant.now(), rejected.isEmpty() ? null : String.join("; ", rejected));
        return result("processed", applied, rejected.size());
    }

    // ── Application ───────────────────────────────────────────────────────────────

    private void apply(String providerName, NormalizedBillingEvent event) {
        // Webhooks arrive unauthenticated: the org is known only through the
        // provider's ids. Resolve via the SECURITY DEFINER lookup so the query is
        // not empty under RLS, then bind the tenant for the writes that follow.
        UUID organizationId = organizationFor(event);
        if (organizationId == null) {
            log.debug("webhook_event_no_org event_type={}", event.eventType());
            return;
        }
        rls.bindTenant(organizationId);

        switch (event.eventType()) {
            case NormalizedBillingEvent.SUBSCRIPTION_ACTIVATED, NormalizedBillingEvent.SUBSCRIPTION_CREATED,
                 NormalizedBillingEvent.SUBSCRIPTION_TRIAL_ENDED -> applyStatus(organizationId, event, "active");
            case NormalizedBillingEvent.SUBSCRIPTION_UPDATED ->
                applyStatus(organizationId, event, event.status() == null ? "active" : event.status());
            case NormalizedBillingEvent.SUBSCRIPTION_CANCELED -> applyStatus(organizationId, event, "canceled");
            case NormalizedBillingEvent.SUBSCRIPTION_PAST_DUE -> applyStatus(organizationId, event, "past_due");
            case NormalizedBillingEvent.INVOICE_PAID -> upsertInvoice(providerName, organizationId, event, "paid");
            case NormalizedBillingEvent.INVOICE_CREATED -> upsertInvoice(providerName, organizationId, event, "open");
            case NormalizedBillingEvent.INVOICE_FAILED -> upsertInvoice(providerName, organizationId, event, "uncollectible");
            case NormalizedBillingEvent.CHECKOUT_COMPLETED -> applyCheckoutCompleted(organizationId, event);
            default -> log.debug("webhook_event_ignored event_type={}", event.eventType());
        }
    }

    /** The baseline's SECURITY DEFINER lookup: customer id first, then subscription id. */
    UUID organizationFor(NormalizedBillingEvent event) {
        return jdbc.sql("SELECT synapse_org_for_provider_ref(:customer, :subscription)")
            .param("customer", event.providerCustomerId()).param("subscription", event.providerSubscriptionId())
            .query(UUID.class).optional().orElse(null);
    }

    private void applyStatus(UUID organizationId, NormalizedBillingEvent event, String targetStatus) {
        Subscription subscription = subscriptions.currentForOrg(organizationId).orElse(null);
        if (subscription == null) {
            log.debug("webhook_status_no_subscription org_id={}", organizationId);
            return;
        }
        subscriptions.applyProviderTransition(subscription, targetStatus, event.currentPeriodEnd());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", targetStatus);
        payload.put("provider_event", event.eventType());
        outbox.append(Events.SUBSCRIPTION_UPDATED, "subscription", subscription.id(), organizationId, payload);
    }

    private void applyCheckoutCompleted(UUID organizationId, NormalizedBillingEvent event) {
        if (event.planKey() == null || event.planKey().isBlank()) {
            log.debug("checkout_completed_no_plan org_id={}", organizationId);
            return;
        }
        Plan plan = subscriptions.planByKey(event.planKey());
        Organization organization = organizations.get(organizationId);
        billing.completeCheckout(organization, plan, event.providerSubscriptionId(), null, BillingService.SOURCE_WEBHOOK);
    }

    /** Provider invoices become {@code invoices} rows keyed on {@code (provider, provider_invoice_id)}. */
    private void upsertInvoice(String providerName, UUID organizationId, NormalizedBillingEvent event, String status) {
        if (event.providerInvoiceId() == null || event.providerInvoiceId().isBlank()) {
            return;
        }
        UUID customerId = customers.findByOrg(organizationId).map(BillingCustomer::id).orElse(null);
        Invoice existing = invoices.findByProviderRef(providerName, event.providerInvoiceId()).orElse(null);
        UUID invoiceId = existing != null ? existing.id()
            : invoices.insert(organizationId, null, providerName, "PHP", 0, 0, 0, "draft", null, null);
        Invoice invoice = invoices.findById(invoiceId).orElseThrow();

        long total = event.amountCents() == null ? 0 : event.amountCents();
        Invoice updated = new Invoice(invoice.id(), invoice.organizationId(), customerId, providerName, event.providerInvoiceId(),
            invoice.number(), event.currency() == null ? "PHP" : event.currency(), invoice.subtotalCents(), invoice.taxCents(), total,
            status, invoice.periodStart(), invoice.periodEnd(), event.hostedUrl(), invoice.pdfUrl(), invoice.issuedAt(),
            "paid".equals(status) ? event.occurredAt() : invoice.paidAt(), invoice.createdAt());
        Invoice saved = invoices.save(updated);

        if ("paid".equals(status)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("total_cents", saved.totalCents());
            payload.put("currency", saved.currency());
            outbox.append(Events.INVOICE_PAID, "invoice", saved.id(), organizationId, payload);
        }
    }

    private static Map<String, Object> result(String status, int applied, Integer rejected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("events_applied", applied);
        if (rejected != null) {
            body.put("events_rejected", rejected);
        }
        return body;
    }
}
