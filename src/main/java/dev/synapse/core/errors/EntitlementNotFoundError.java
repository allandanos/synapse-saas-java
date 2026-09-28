package dev.synapse.core.errors;

import java.util.Map;

/** Grant id unknown or belongs to another organization. */
public final class EntitlementNotFoundError extends DomainError {
    public static final int STATUS = 404;
    public static final String TITLE = "entitlement_not_found";

    public EntitlementNotFoundError(String message) {
        this(message, Map.of());
    }

    public EntitlementNotFoundError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
