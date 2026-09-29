package dev.synapse.core.errors;

import java.util.Map;

/** No such webhook delivery in this organization (cross-tenant ids are 404 too). */
public final class WebhookDeliveryNotFoundError extends DomainError {
    public static final int STATUS = 404;
    public static final String TITLE = "webhook_delivery_not_found";

    public WebhookDeliveryNotFoundError(String message) {
        this(message, Map.of());
    }

    public WebhookDeliveryNotFoundError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
