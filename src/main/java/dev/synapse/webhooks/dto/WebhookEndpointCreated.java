package dev.synapse.webhooks.dto;

import dev.synapse.webhooks.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The creation response: {@link WebhookEndpointRead} plus the secret, which appears exactly once. */
public record WebhookEndpointCreated(UUID id, String url, String description, List<String> events, boolean isActive,
                                     Instant createdAt, String secret) {

    public static WebhookEndpointCreated from(WebhookEndpoint e, String secret) {
        return new WebhookEndpointCreated(e.id(), e.url(), e.description(), e.events(), e.active(), e.createdAt(), secret);
    }
}
