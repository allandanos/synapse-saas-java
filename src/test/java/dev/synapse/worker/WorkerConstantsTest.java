package dev.synapse.worker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.synapse.billing.BillingCapability;
import dev.synapse.billing.BillingProviderRegistry;
import dev.synapse.core.outbox.Events;
import dev.synapse.webhooks.WebhookDeliveryService;
import dev.synapse.worker.jobs.AdvanceRecurringBillingJob;
import dev.synapse.worker.jobs.DeliverWebhooksJob;
import dev.synapse.worker.jobs.EnsurePartitionsJob;
import dev.synapse.worker.jobs.OutboxDispatchJob;
import dev.synapse.worker.jobs.PurgeExpiredJob;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The worker's contract with itself: batch sizes, backoff ladders, retention and
 * the event audience that decides whether a tenant can ever see an event.
 * These numbers are copied from the reference and a drift here is a real bug.
 */
class WorkerConstantsTest {

    @Test
    void theOutboxLadderIsEightStepsEndingInHourlyRetries() {
        assertThat(OutboxDispatchJob.OUTBOX_BACKOFF_SECONDS).containsExactly(5, 30, 120, 600, 1800, 3600, 3600, 3600);
        assertThat(OutboxDispatchJob.OUTBOX_MAX_ATTEMPTS).isEqualTo(8).isEqualTo(OutboxDispatchJob.OUTBOX_BACKOFF_SECONDS.size());
        assertThat(OutboxDispatchJob.OUTBOX_BATCH).isEqualTo(20);
    }

    @Test
    void theDeliveryLadderIsFiveStepsAndOneMoreAttemptThanSteps() {
        assertThat(WebhookDeliveryService.DELIVERY_BACKOFF_SECONDS).containsExactly(60, 300, 1800, 7200, 21600);
        assertThat(WebhookDeliveryService.MAX_DELIVERY_ATTEMPTS).isEqualTo(6);
        assertThat(DeliverWebhooksJob.DELIVERY_BATCH).isEqualTo(20);
    }

    @Test
    void theRemainingBatchAndRetentionConstantsMatchTheReference() {
        assertThat(AdvanceRecurringBillingJob.RENEWAL_BATCH).isEqualTo(100);
        assertThat(EnsurePartitionsJob.PARTITION_MONTHS_AHEAD).isEqualTo(3);
        assertThat(PurgeExpiredJob.IDEMPOTENCY_RETENTION_DAYS).isEqualTo(90);
        assertThat(PurgeExpiredJob.DELIVERY_RETENTION_DAYS).isEqualTo(30);
        // The failure audit trail outlives routine rows
        assertThat(PurgeExpiredJob.EXHAUSTED_DELIVERY_RETENTION_DAYS).isEqualTo(90)
            .isGreaterThan(PurgeExpiredJob.DELIVERY_RETENTION_DAYS);
        assertThat(PurgeExpiredJob.OUTBOX_RETENTION_DAYS).isEqualTo(7);
    }

    @Test
    void theJobTableIsTheReferencesDispatchOrder() {
        assertThat(JobRegistry.JOB_NAMES).containsExactly("dispatch_outbox", "deliver_webhooks", "rollup_usage",
            "expire_entitlements", "advance_recurring_billing", "ensure_partitions", "purge_expired");
    }

    /** Internal events carry invite tokens, reset links and invoice instructions: never fanned out. */
    @Test
    void onlyTheFourInternalEventsAreInternal() {
        assertThat(Events.INTERNAL_EVENTS).containsExactlyInAnyOrder(
            Events.MEMBER_INVITE_EMAIL, Events.USER_PASSWORD_RESET_LINK, Events.INVOICE_EMAIL, Events.AUTHZ_TUPLES_CHANGED);
        assertThat(Events.audienceFor(Events.INVOICE_EMAIL)).isEqualTo(Events.AUDIENCE_INTERNAL);
        assertThat(Events.audienceFor(Events.INVOICE_CREATED)).isEqualTo(Events.AUDIENCE_PUBLIC);
        assertThat(Events.audienceFor(Events.INVOICE_PAID)).isEqualTo(Events.AUDIENCE_PUBLIC);
    }

    /** The renewal job bills exactly the providers without provider-side recurring. */
    @Test
    void locallyBilledProvidersAreTheOnesWithoutRecurringHosted() {
        List<String> local = BillingProviderRegistry.locallyBilledProviderNames();
        assertThat(local).containsExactly("manual", "xendit", "paymongo", "paddle");
        assertThat(local).doesNotContain("stripe");
        assertThat(BillingProviderRegistry.CAPABILITIES.get("stripe")).contains(BillingCapability.RECURRING_HOSTED);
        local.forEach(name -> assertThat(BillingProviderRegistry.CAPABILITIES.get(name))
            .doesNotContain(BillingCapability.RECURRING_HOSTED));
    }

    /** Only a provider with no payment truth of its own may accept a client confirmation. */
    @Test
    void onlyManualCarriesClientConfirm() {
        BillingProviderRegistry.CAPABILITIES.forEach((name, capabilities) ->
            assertThat(capabilities.contains(BillingCapability.CLIENT_CONFIRM)).isEqualTo("manual".equals(name)));
    }

    @Test
    void theAdvisoryLockKeyIsNamespacedPerJob() {
        assertThat(AdvisoryLock.KEY_PREFIX).isEqualTo("job:");
    }
}
