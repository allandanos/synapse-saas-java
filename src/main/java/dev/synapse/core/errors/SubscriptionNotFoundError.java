package dev.synapse.core.errors;

import java.util.Map;

/** No occupying subscription (or nothing to resume). */
public final class SubscriptionNotFoundError extends DomainError {
    public static final int STATUS = 404;
    public static final String TITLE = "subscription_not_found";

    public SubscriptionNotFoundError(String message) {
        this(message, Map.of());
    }

    public SubscriptionNotFoundError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
