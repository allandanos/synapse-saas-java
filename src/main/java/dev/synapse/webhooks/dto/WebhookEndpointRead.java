package dev.synapse.webhooks.dto;

import dev.synapse.webhooks.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What a caller may ever read back — deliberately without the secret. */
public record WebhookEndpointRead(UUID id, String url, String description, List<String> events, boolean isActive,
                                  Instant createdAt) {

    public static WebhookEndpointRead from(WebhookEndpoint e) {
        return new WebhookEndpointRead(e.id(), e.url(), e.description(), e.events(), e.active(), e.createdAt());
    }
}
