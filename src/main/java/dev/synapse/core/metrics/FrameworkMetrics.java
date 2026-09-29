package dev.synapse.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The reference's domain counters (reference: {@code core/metrics.py}), exported
 * at {@code /metrics} as {@code synapse_usage_limited_total{metric}} and
 * {@code synapse_feature_gated_total{feature}}. Metrics must never fail a request.
 */
@Component
public class FrameworkMetrics {

    private static final Logger log = LoggerFactory.getLogger(FrameworkMetrics.class);

    private final MeterRegistry registry;

    public FrameworkMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A request rejected for exceeding a plan limit (402). */
    public void usageLimited(String metric) {
        increment("synapse_usage_limited", "Requests rejected for exceeding plan limits (402)", "metric", metric);
    }

    /** A request denied by a feature gate (403 feature_not_entitled). */
    public void featureGated(String feature) {
        increment("synapse_feature_gated", "Requests denied by feature gates (403 feature_not_entitled)", "feature", feature);
    }

    /** One webhook delivery attempt's outcome: delivered | failed | exhausted. */
    public void webhookDelivery(String outcome) {
        increment("synapse_webhook_deliveries", "Outbound webhook delivery attempts by outcome", "outcome", outcome);
    }

    /** A business event durably published by the outbox dispatcher. */
    public void businessEvent(String eventType) {
        increment("synapse_business_events", "Domain events published from the outbox", "event", eventType);
    }

    /** An outbox event that exhausted its attempts and was dead-lettered. */
    public void outboxDead(String eventType) {
        increment("synapse_outbox_dead", "Outbox events dead-lettered after the maximum attempts", "event", eventType);
    }

    /** An email dispatch outcome: sent | suppressed | failed. */
    public void email(String outcome) {
        increment("synapse_emails", "Notification emails by outcome", "outcome", outcome);
    }

    /** One worker job run: ok | error. */
    public void workerJob(String job, String outcome) {
        try {
            io.micrometer.core.instrument.Counter.builder("synapse_worker_jobs")
                .description("Worker job runs by outcome").tag("job", job).tag("outcome", outcome).register(registry).increment();
        } catch (RuntimeException e) {
            log.debug("metrics_inc_failed metric=synapse_worker_jobs error={}", e.toString());
        }
    }

    private void increment(String name, String description, String tag, String value) {
        try {
            Counter.builder(name).description(description).tag(tag, value).register(registry).increment();
        } catch (RuntimeException e) {
            log.debug("metrics_inc_failed metric={} error={}", name, e.toString());
        }
    }
}
