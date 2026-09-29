package dev.synapse.webhooks;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Row of {@code webhook_deliveries}: one attempt ladder per (endpoint, event). */
public record WebhookDelivery(UUID id, UUID endpointId, UUID organizationId, UUID outboxEventId, String eventType,
                              Map<String, Object> payload, String status, int attempts, int maxAttempts, Instant nextAttemptAt,
                              Integer lastResponseCode, String lastError, String responseExcerpt, Instant deliveredAt, Instant createdAt) {

    public WebhookDelivery {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
