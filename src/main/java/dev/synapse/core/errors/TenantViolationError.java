package dev.synapse.core.errors;

import java.util.Map;

/** An operation tried to cross the tenant boundary (e.g. a storage key outside the org prefix). */
public final class TenantViolationError extends DomainError {
    public static final int STATUS = 403;
    public static final String TITLE = "tenant_violation";

    public TenantViolationError(String message) {
        this(message, Map.of());
    }

    public TenantViolationError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
