package dev.synapse.billing.invoicing;

import dev.synapse.core.audit.AuditService;
import dev.synapse.core.errors.InvoiceNotFoundError;
import dev.synapse.core.errors.ValidationFailedError;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import dev.synapse.entitlements.EntitlementResolver.EffectiveEntitlements;
import dev.synapse.entitlements.EntitlementResolver.Limit;
import dev.synapse.entitlements.EntitlementResolver.Overage;
import dev.synapse.entitlements.EntitlementService;
import dev.synapse.subscriptions.Subscription;
import dev.synapse.subscriptions.SubscriptionRepository;
import dev.synapse.usage.UsageRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Framework-native invoice generation and payment recording
 * (reference: {@code billing/invoicing.py}).
 *
 * <p>Orgs on the Manual provider — or on any provider we bill locally, or an
 * enterprise contract — get real invoices generated here rather than an event
 * ledger mirrored from a payment provider. Provider-sourced invoices (webhook
 * upserts) never carry lines: their shape is the provider's, ours is ours.
 */
@Service
public class InvoicingService {

    private static final Logger log = LoggerFactory.getLogger(InvoicingService.class);

    /** The reference's `InvoicingService` writes `provider = "synapse"` on its own invoices. */
    public static final String SELF_PROVIDER = "synapse";
    public static final String EVENT_INVOICE_FINALIZED = "invoice.finalized";
    public static final String EVENT_PAYMENT_RECORDED = "invoice.payment_recorded";
    public static final String EVENT_INVOICE_VOIDED = "invoice.voided";

    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final SubscriptionRepository subscriptions;
    private final EntitlementService entitlements;
    private final UsageRepository usage;
    private final OutboxWriter outbox;
    private final AuditService audit;

    public InvoicingService(InvoiceRepository invoices, InvoiceLineRepository lines, SubscriptionRepository subscriptions,
                            EntitlementService entitlements, UsageRepository usage, OutboxWriter outbox, AuditService audit) {
        this.invoices = invoices;
        this.lines = lines;
        this.subscriptions = subscriptions;
        this.entitlements = entitlements;
        this.usage = usage;
        this.outbox = outbox;
        this.audit = audit;
    }

    // ── Draft: build lines from the org's plan + usage ────────────────────────────

    /**
     * Create — or return the existing — draft for the period. Idempotent per
     * {@code (org, period)} so re-running a billing job is safe.
     */
    @Transactional
    public Invoice draftForOrg(UUID organizationId, LocalDate period, UUID createdByUserId) {
        LocalDate periodStart = period != null ? period : monthBucket(Instant.now());
        Invoice existing = invoices.findDraft(organizationId, periodStart).orElse(null);
        if (existing != null) {
            return existing;
        }

        Subscription subscription = activeSubscription(organizationId);
        Map<String, Object> snapshot = subscription.planSnapshot();
        List<InvoiceLine.Draft> draftLines = new ArrayList<>();

        // 1 — plan charge, from the purchase-time snapshot (grandfathering)
        String planKey = String.valueOf(snapshot.getOrDefault("key", "unknown"));
        long planCents = asLong(snapshot.get("price_cents"));
        if (planCents > 0) {
            String name = snapshot.get("name") == null ? planKey : String.valueOf(snapshot.get("name"));
            String interval = snapshot.get("interval") == null ? "month" : String.valueOf(snapshot.get("interval"));
            draftLines.add(InvoiceLine.Draft.of("plan", name + " plan (" + interval + "ly)", 1, planCents, planCents));
        }

        // 2 — metered overage, priced by the ENTITLEMENTS resolver so addon grants
        //     shape billing exactly like they shape enforcement (one source of truth)
        draftLines.addAll(overageLines(organizationId, periodStart));

        // 3 — prorated adjustments queued by mid-period plan changes, drained here
        draftLines.addAll(adjustmentLines(subscription.pendingAdjustments()));
        List<Map<String, Object>> remainingAdjustments = List.of();

        long subtotal = draftLines.stream().mapToLong(InvoiceLine.Draft::amountCents).sum();
        if (subtotal < 0) {
            // Nothing to collect; carry the remaining credit into the next draft.
            Map<String, Object> carry = new LinkedHashMap<>();
            carry.put("kind", "credit_carryover");
            carry.put("amount_cents", subtotal);
            carry.put("description", "Credit carried forward from the previous invoice");
            carry.put("created_at", Instant.now().toString());
            remainingAdjustments = List.of(carry);
            subtotal = 0;
        }
        subscriptions.save(subscription.withPendingAdjustments(remainingAdjustments));

        String currency = snapshot.get("currency") == null ? "PHP" : String.valueOf(snapshot.get("currency"));
        UUID id = invoices.insert(organizationId, null, SELF_PROVIDER, currency, subtotal, 0, subtotal, "draft",
            startOfMonth(periodStart), endOfMonth(periodStart));
        lines.insertAll(id, organizationId, draftLines);

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("period", periodStart.toString());
        diff.put("lines", draftLines.size());
        diff.put("subtotal_cents", subtotal);
        audit.log(Events.INVOICE_CREATED, organizationId, createdByUserId, "invoice", id, diff);
        log.info("invoice_drafted org={} period={} lines={}", organizationId, periodStart, draftLines.size());
        return invoices.findById(id).orElseThrow();
    }

    // ── Finalize: number it, open it ──────────────────────────────────────────────

    @Transactional
    public Invoice finalize(UUID invoiceId, UUID organizationId) {
        Invoice invoice = scoped(invoiceId, organizationId);
        InvoiceTransitions.assertTransition(invoice.status(), "open");
        invoices.lockOrganization(organizationId);
        String number = InvoiceNumbering.next(invoices.numberedCount(organizationId), Instant.now());
        Invoice saved = invoices.save(invoice.withStatus("open").withIssued(number, Instant.now()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("number", saved.number());
        payload.put("total_cents", saved.totalCents());
        payload.put("status", "open");
        outbox.append(Events.INVOICE_CREATED, "invoice", saved.id(), organizationId, payload);
        // Delivery email rides the same outbox; the worker renders and attaches the PDF.
        outbox.append(Events.INVOICE_EMAIL, "invoice", saved.id(), organizationId,
            Map.of("invoice_id", saved.id().toString(), "reason", "finalized"));
        audit.log(EVENT_INVOICE_FINALIZED, organizationId, null, "invoice", saved.id(), Map.of("number", saved.number()));
        return saved;
    }

    // ── Record a payment ─────────────────────────────────────────────────────────

    @Transactional
    public Invoice recordPayment(UUID invoiceId, UUID organizationId, long amountCents, String reference) {
        Invoice invoice = scoped(invoiceId, organizationId);
        InvoiceTransitions.assertTransition(invoice.status(), "paid");
        if (amountCents < invoice.totalCents()) {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("expected", invoice.totalCents());
            extras.put("received", amountCents);
            throw new ValidationFailedError("Partial payments are not supported in v1", extras);
        }
        Invoice saved = invoices.save(invoice.withStatus("paid").withPaidAt(Instant.now()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total_cents", saved.totalCents());
        payload.put("currency", saved.currency());
        payload.put("reference", reference);
        outbox.append(Events.INVOICE_PAID, "invoice", saved.id(), organizationId, payload);
        outbox.append(Events.INVOICE_EMAIL, "invoice", saved.id(), organizationId,
            Map.of("invoice_id", saved.id().toString(), "reason", "paid"));
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("amount_cents", amountCents);
        diff.put("reference", reference);
        audit.log(EVENT_PAYMENT_RECORDED, organizationId, null, "invoice", saved.id(), diff);
        return saved;
    }

    @Transactional
    public Invoice voidInvoice(UUID invoiceId, UUID organizationId) {
        Invoice invoice = scoped(invoiceId, organizationId);
        InvoiceTransitions.assertTransition(invoice.status(), "void");
        Invoice saved = invoices.save(invoice.withStatus("void"));
        audit.log(EVENT_INVOICE_VOIDED, organizationId, null, "invoice", saved.id(), null);
        return saved;
    }

    // ── Queries ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Invoice get(UUID invoiceId, UUID organizationId) {
        return scoped(invoiceId, organizationId);
    }

    @Transactional(readOnly = true)
    public List<Invoice> forOrg(UUID organizationId) {
        return invoices.forOrg(organizationId, 50);
    }

    @Transactional(readOnly = true)
    public List<InvoiceLine> linesFor(UUID invoiceId, UUID organizationId) {
        return lines.forInvoice(scoped(invoiceId, organizationId).id());
    }

    /** The org an invoice belongs to — how the operator routes scope themselves. */
    @Transactional(readOnly = true)
    public UUID organizationOf(UUID invoiceId) {
        UUID organizationId = invoices.organizationOf(invoiceId);
        if (organizationId == null) {
            throw new InvoiceNotFoundError("Invoice not found", Map.of("invoice_id", invoiceId.toString()));
        }
        return organizationId;
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private Invoice scoped(UUID invoiceId, UUID organizationId) {
        Invoice invoice = invoices.findById(invoiceId).orElse(null);
        if (invoice == null || !invoice.organizationId().equals(organizationId)) {
            throw new InvoiceNotFoundError("Invoice not found"); // cross-tenant 404
        }
        return invoice;
    }

    private Subscription activeSubscription(UUID organizationId) {
        return subscriptions.currentForOrg(organizationId)
            .orElseThrow(() -> new ValidationFailedError("Organization has no active subscription to bill"));
    }

    /** One line per metric whose usage exceeded its included amount AND has a price. */
    private List<InvoiceLine.Draft> overageLines(UUID organizationId, LocalDate period) {
        EffectiveEntitlements effective = entitlements.effectiveForOrg(organizationId);
        List<InvoiceLine.Draft> out = new ArrayList<>();
        for (String metric : effective.limits().keySet().stream().sorted().toList()) {
            Limit limit = effective.limits().get(metric);
            if (limit.value() == null || limit.overage() == null) {
                continue;
            }
            long used = usage.total(organizationId, metric, period);
            long unitsOver = used - limit.value();
            if (unitsOver <= 0) {
                continue;
            }
            Overage overage = limit.overage();
            Overage.Bill bill = overage.bill(unitsOver);
            String per = overage.unit() > 1 ? " (per " + grouped(overage.unit()) + ")" : "";
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("units_over", unitsOver);
            properties.put("included", limit.value());
            properties.put("overage_unit", overage.unit());
            out.add(new InvoiceLine.Draft("overage", metric + " overage — " + grouped(unitsOver) + " units over plan" + per,
                bill.quantity(), overage.priceCents(), bill.amountCents(), metric, properties));
        }
        return out;
    }

    /** Pending proration/credit entries → invoice lines (a credit when negative). */
    static List<InvoiceLine.Draft> adjustmentLines(List<Map<String, Object>> adjustments) {
        List<InvoiceLine.Draft> out = new ArrayList<>();
        for (Map<String, Object> adjustment : adjustments) {
            long amount = asLong(adjustment.get("amount_cents"));
            if (amount == 0) {
                continue;
            }
            Object description = adjustment.get("description");
            if (description == null) {
                description = adjustment.getOrDefault("kind", "adjustment");
            }
            Map<String, Object> properties = new LinkedHashMap<>(adjustment);
            properties.remove("description");
            properties.remove("amount_cents");
            out.add(new InvoiceLine.Draft(amount < 0 ? "credit" : "custom", String.valueOf(description), 1, amount, amount, null, properties));
        }
        return out;
    }

    public static LocalDate monthBucket(Instant now) {
        return now.atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    }

    static Instant startOfMonth(LocalDate periodStart) {
        return periodStart.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Last day of the period's month at 23:59:59 UTC, as the reference computes it. */
    static Instant endOfMonth(LocalDate periodStart) {
        LocalDate lastDay = periodStart.plusMonths(1).withDayOfMonth(1).minusDays(1);
        return lastDay.atTime(LocalTime.of(23, 59, 59)).toInstant(ZoneOffset.UTC);
    }

    /** Python's `{n:,}` thousands separator, which the line descriptions carry. */
    static String grouped(long value) {
        return String.format("%,d", value);
    }

    static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
