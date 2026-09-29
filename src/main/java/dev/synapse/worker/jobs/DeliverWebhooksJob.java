package dev.synapse.worker.jobs;

import dev.synapse.webhooks.WebhookDeliveryRepository;
import dev.synapse.webhooks.WebhookDeliveryService;
import dev.synapse.worker.Job;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attempt pending deliveries whose backoff has elapsed (reference:
 * {@code worker/jobs.py:deliver_webhooks}). Rows are claimed with
 * {@code SKIP LOCKED} so N workers never POST the same delivery twice.
 */
@Component
public class DeliverWebhooksJob implements Job {

    public static final String NAME = "deliver_webhooks";
    public static final int DELIVERY_BATCH = 20;

    private final WebhookDeliveryRepository deliveries;
    private final WebhookDeliveryService service;

    public DeliverWebhooksJob(WebhookDeliveryRepository deliveries, WebhookDeliveryService service) {
        this.deliveries = deliveries;
        this.service = service;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    @Transactional
    public int runOnce() {
        List<UUID> due = deliveries.claimDue(DELIVERY_BATCH);
        int delivered = 0;
        for (UUID id : due) {
            if (service.deliver(id)) {
                delivered++;
            }
        }
        return delivered;
    }
}
