package dev.synapse.webhooks.dto;

import dev.synapse.webhooks.WebhookDelivery;
import java.time.Instant;
import java.util.UUID;

public record WebhookDeliveryRead(UUID id, UUID endpointId, String eventType, String status, int attempts, int maxAttempts,
                                  Instant nextAttemptAt, Integer lastResponseCode, String lastError, Instant deliveredAt,
                                  Instant createdAt) {

    public static WebhookDeliveryRead from(WebhookDelivery d) {
        return new WebhookDeliveryRead(d.id(), d.endpointId(), d.eventType(), d.status(), d.attempts(), d.maxAttempts(),
            d.nextAttemptAt(), d.lastResponseCode(), d.lastError(), d.deliveredAt(), d.createdAt());
    }
}
