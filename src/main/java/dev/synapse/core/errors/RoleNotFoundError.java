package dev.synapse.core.errors;

import java.util.Map;

/** Role key/id not found in the tenant's scope (system roles are not tenant-scoped). */
public final class RoleNotFoundError extends DomainError {
    public static final int STATUS = 404;
    public static final String TITLE = "role_not_found";

    public RoleNotFoundError(String message) {
        this(message, Map.of());
    }

    public RoleNotFoundError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
