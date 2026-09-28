package dev.synapse.core.errors;

import java.util.Map;

/** API key not found in the tenant's scope (identical to a foreign key). */
public final class ApiKeyNotFoundError extends DomainError {
    public static final int STATUS = 404;
    public static final String TITLE = "api_key_not_found";

    public ApiKeyNotFoundError(String message) {
        this(message, Map.of());
    }

    public ApiKeyNotFoundError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
