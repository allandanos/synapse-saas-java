package dev.synapse.core.errors;

import java.util.Map;

/** No tenant context is active for an operation that needs one. */
public final class TenantContextMissingError extends DomainError {
    public static final int STATUS = 400;
    public static final String TITLE = "tenant_context_missing";

    public TenantContextMissingError(String message) {
        this(message, Map.of());
    }

    public TenantContextMissingError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
