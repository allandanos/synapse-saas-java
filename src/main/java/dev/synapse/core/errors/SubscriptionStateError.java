package dev.synapse.core.errors;

import java.util.Map;

/** The state machine forbids the transition ({@code from}, {@code to}, {@code allowed}). */
public final class SubscriptionStateError extends DomainError {
    public static final int STATUS = 409;
    public static final String TITLE = "invalid_subscription_transition";

    public SubscriptionStateError(String message) {
        this(message, Map.of());
    }

    public SubscriptionStateError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
