package dev.synapse.webhooks.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Empty {@code events} ⇒ every public event type. */
public record WebhookEndpointCreate(@NotNull String url, List<String> events, String description) {

    public List<String> eventsOrEmpty() {
        return events == null ? List.of() : events;
    }
}
