package dev.synapse.core.errors;

import java.util.Map;

/** Client-side checkout confirmation on a provider that verifies payment itself. */
public final class CheckoutConfirmNotAllowedError extends DomainError {
    public static final int STATUS = 409;
    public static final String TITLE = "checkout_confirm_not_allowed";

    public CheckoutConfirmNotAllowedError(String message) {
        this(message, Map.of());
    }

    public CheckoutConfirmNotAllowedError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
