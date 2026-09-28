package dev.synapse.core.errors;

import java.util.Map;

/** Org/membership/row could not be resolved OR the caller is not a member. Deliberately 404 — never leak existence. */
public final class NotFoundError extends DomainError {
    public static final int STATUS = 404;
    public static final String TITLE = "not_found";

    public NotFoundError(String message) {
        this(message, Map.of());
    }

    public NotFoundError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
