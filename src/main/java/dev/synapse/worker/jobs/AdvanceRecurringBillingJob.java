package dev.synapse.worker.jobs;

import dev.synapse.billing.BillingProviderRegistry;
import dev.synapse.billing.invoicing.Invoice;
import dev.synapse.billing.invoicing.InvoicingService;
import dev.synapse.subscriptions.Subscription;
import dev.synapse.subscriptions.SubscriptionRepository;
import dev.synapse.worker.Job;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Renew the subscriptions WE bill: invoice the period that just ended, then
 * roll it forward (reference: {@code worker/jobs.py:advance_recurring_billing}).
 *
 * <p>Applies to every provider without {@code recurring_hosted} (manual,
 * Xendit, PayMongo, Paddle) — hosted providers renew on their side and report
 * through webhooks. Rows are claimed with {@code SKIP LOCKED} so N workers
 * never renew the same subscription twice, and each renewal is its own
 * savepoint so one bad subscription cannot block the batch. Invoices always go
 * through the invoicing engine (lines, number, overage, email).
 */
@Component
public class AdvanceRecurringBillingJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(AdvanceRecurringBillingJob.class);

    public static final String NAME = "advance_recurring_billing";
    public static final int RENEWAL_BATCH = 100;
    private static final Duration MONTH = Duration.ofDays(30);
    private static final Duration YEAR = Duration.ofDays(365);

    private final JdbcClient jdbc;
    private final SubscriptionRepository subscriptions;
    private final InvoicingService invoicing;
    private final TransactionTemplate batch;
    private final TransactionTemplate savepoint;

    public AdvanceRecurringBillingJob(JdbcClient jdbc, SubscriptionRepository subscriptions, InvoicingService invoicing,
                                      PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.invoicing = invoicing;
        this.batch = new TransactionTemplate(transactions);
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int runOnce() {
        return batch.execute(status -> renewBatch());
    }

    private int renewBatch() {
        String[] providers = BillingProviderRegistry.locallyBilledProviderNames().toArray(String[]::new);
        List<UUID> due = jdbc.sql("""
                SELECT id FROM subscriptions
                WHERE status = 'active'
                  AND current_period_end <= now()
                  AND cancel_at_period_end = false
                  AND (provider IS NULL OR provider = ANY(:providers))
                ORDER BY current_period_end
                LIMIT :batch
                FOR UPDATE SKIP LOCKED
                """)
            .param("providers", providers).param("batch", RENEWAL_BATCH).query(UUID.class).list();

        int renewed = 0;
        for (UUID id : due) {
            try {
                savepoint.executeWithoutResult(status -> renew(id));
                renewed++;
            } catch (RuntimeException e) { // isolate one bad renewal, keep the batch
                log.error("recurring_billing_failed subscription_id={} error={}", id, e.toString(), e);
            }
        }
        return renewed;
    }

    /** Bill the period that just ended (in arrears), then roll the period forward. */
    private void renew(UUID subscriptionId) {
        Subscription subscription = subscriptions.findById(subscriptionId).orElse(null);
        if (subscription == null) {
            return;
        }
        Map<String, Object> snapshot = subscription.planSnapshot();
        long price = InvoicingService.asLong(snapshot.get("price_cents"));
        if (price > 0 || !subscription.pendingAdjustments().isEmpty()) {
            Invoice invoice = invoicing.draftForOrg(subscription.organizationId(),
                subscription.currentPeriodStart().atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1), null);
            if ("draft".equals(invoice.status())) {
                invoicing.finalize(invoice.id(), subscription.organizationId());
            }
        }
        Duration interval = "year".equals(String.valueOf(snapshot.get("interval"))) ? YEAR : MONTH;
        subscriptions.save(subscription.withPeriod(subscription.currentPeriodEnd(), subscription.currentPeriodEnd().plus(interval)));
    }
}
