package dev.synapse.worker.jobs;

import dev.synapse.core.metrics.FrameworkMetrics;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxRepository;
import dev.synapse.core.outbox.OutboxRepository.OutboxEvent;
import dev.synapse.notifications.NotificationHandlers;
import dev.synapse.webhooks.WebhookDeliveryRepository;
import dev.synapse.webhooks.WebhookDeliveryService;
import dev.synapse.webhooks.WebhookEndpointRepository;
import dev.synapse.worker.Job;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drain the outbox (reference: {@code worker/jobs.py:_dispatch_outbox_impl}).
 *
 * <p>Per event, in its own savepoint: a {@code public} event fans out to one
 * {@code webhook_deliveries} row per active endpoint subscribed to the type
 * (an empty filter means all); {@code internal} events NEVER fan out — they
 * carry invite tokens, reset links and invoice instructions. The row is then
 * marked published. On failure the savepoint rolls back and
 * {@code attempts}/{@code last_error}/{@code next_attempt_at} are written;
 * after {@link #OUTBOX_MAX_ATTEMPTS} the event is dead-lettered so one poison
 * row cannot pin the batch forever.
 *
 * <p>In-process consumers (emails) run AFTER the commit: a retry can no longer
 * send the same invite or invoice twice.
 */
@Component
public class OutboxDispatchJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchJob.class);

    public static final String NAME = "dispatch_outbox";
    public static final int OUTBOX_BATCH = 20;
    public static final int OUTBOX_MAX_ATTEMPTS = 8;
    /** 5 s, 30 s, 2 m, 10 m, 30 m, 1 h, 1 h, 1 h. */
    public static final List<Integer> OUTBOX_BACKOFF_SECONDS = List.of(5, 30, 120, 600, 1800, 3600, 3600, 3600);

    private final OutboxRepository outbox;
    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final NotificationHandlers notifications;
    private final FrameworkMetrics metrics;
    private final TransactionTemplate batch;
    private final TransactionTemplate savepoint;

    public OutboxDispatchJob(OutboxRepository outbox, WebhookEndpointRepository endpoints, WebhookDeliveryRepository deliveries,
                             NotificationHandlers notifications, FrameworkMetrics metrics, PlatformTransactionManager transactions) {
        this.outbox = outbox;
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.metrics = metrics;
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
        // The batch commits before the consumers run, so a failure below cannot resend.
        List<Dispatched> dispatched = batch.execute(status -> dispatchBatch());
        // In-process consumers run only once the events are durably published.
        // Failures are logged; the OpenFGA tuple sync joins them in milestone 7.
        for (Dispatched event : dispatched) {
            try {
                notifications.handleEvent(event.eventType(), event.payload());
            } catch (RuntimeException e) {
                log.warn("internal_consumer_failed consumer=notifications event_type={} error={}", event.eventType(), e.toString());
            }
        }
        return dispatched.size();
    }

    private record Dispatched(String eventType, Map<String, Object> payload) {}

    private List<Dispatched> dispatchBatch() {
        List<UUID> claimed = outbox.claimPending(OUTBOX_BATCH);
        List<Dispatched> dispatched = new ArrayList<>();
        for (UUID id : claimed) {
            OutboxEvent event = outbox.findById(id).orElse(null);
            if (event == null) {
                continue;
            }
            try {
                savepoint.executeWithoutResult(status -> publish(event));
            } catch (RuntimeException e) {
                recordFailure(event, e);
                continue;
            }
            metrics.businessEvent(event.eventType());
            dispatched.add(new Dispatched(event.eventType(), event.payload()));
        }
        return dispatched;
    }

    private void publish(OutboxEvent event) {
        if (Events.AUDIENCE_PUBLIC.equals(event.audience()) && event.organizationId() != null) {
            for (UUID endpointId : endpoints.subscribedTo(event.organizationId(), event.eventType())) {
                deliveries.insert(endpointId, event.organizationId(), event.id(), event.eventType(), event.payload(),
                    WebhookDeliveryService.MAX_DELIVERY_ATTEMPTS);
            }
        }
        outbox.markPublished(event.id(), Instant.now());
    }

    /** Retry bookkeeping for one failed event (its savepoint has already rolled back). */
    private void recordFailure(OutboxEvent event, RuntimeException cause) {
        int attempts = event.attempts() + 1;
        String error = truncate(String.valueOf(cause));
        if (attempts >= OUTBOX_MAX_ATTEMPTS) {
            outbox.markFailure(event.id(), attempts, error, null, Instant.now());
            metrics.outboxDead(event.eventType());
            log.error("outbox_event_dead_lettered event_id={} event_type={} attempts={} error={}", event.id(), event.eventType(),
                attempts, error);
            return;
        }
        int backoff = OUTBOX_BACKOFF_SECONDS.get(Math.min(attempts - 1, OUTBOX_BACKOFF_SECONDS.size() - 1));
        outbox.markFailure(event.id(), attempts, error, Instant.now().plusSeconds(backoff), null);
        log.warn("outbox_event_failed event_id={} event_type={} attempts={} retry_in_seconds={} error={}", event.id(),
            event.eventType(), attempts, backoff, error);
    }

    private static String truncate(String error) {
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }
}
