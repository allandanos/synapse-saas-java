package dev.synapse.core.errors;

import java.util.Map;

/** Webhook endpoint missing or owned by another tenant. */
public final class WebhookEndpointNotFoundError extends DomainError {
    public static final int STATUS = 404;
    public static final String TITLE = "webhook_endpoint_not_found";

    public WebhookEndpointNotFoundError(String message) {
        this(message, Map.of());
    }

    public WebhookEndpointNotFoundError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
