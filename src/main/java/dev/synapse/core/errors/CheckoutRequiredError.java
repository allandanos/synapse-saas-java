package dev.synapse.core.errors;

import java.util.Map;

/** A hosted billing provider cannot change a plan that was never purchased through it. */
public final class CheckoutRequiredError extends DomainError {
    public static final int STATUS = 409;
    public static final String TITLE = "checkout_required";

    public CheckoutRequiredError(String message) {
        this(message, Map.of());
    }

    public CheckoutRequiredError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
