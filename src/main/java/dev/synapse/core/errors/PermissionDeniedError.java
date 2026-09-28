package dev.synapse.core.errors;

import java.util.Map;

/** The principal lacks the permission the route requires (or an API key exceeds its creator). */
public final class PermissionDeniedError extends DomainError {
    public static final int STATUS = 403;
    public static final String TITLE = "permission_denied";

    public PermissionDeniedError(String message) {
        this(message, Map.of());
    }

    public PermissionDeniedError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
