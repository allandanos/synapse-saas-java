package dev.synapse.webhooks;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Row of {@code webhook_endpoints}. {@code events} empty ⇒ every event type;
 * {@code secretEncrypted} is a Fernet token (see {@link FernetCodec}).
 */
public record WebhookEndpoint(UUID id, UUID organizationId, String url, byte[] secretEncrypted, String description,
                              List<String> events, boolean active, Instant createdAt) {

    public WebhookEndpoint {
        secretEncrypted = secretEncrypted.clone();
        events = List.copyOf(events);
    }

    @Override
    public byte[] secretEncrypted() {
        return secretEncrypted.clone();
    }
}
