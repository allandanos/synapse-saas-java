package dev.synapse.core.errors;

import java.util.Map;

/** Missing or invalid credential. The detail never says which check failed. */
public final class AuthenticationError extends DomainError {
    public static final int STATUS = 401;
    public static final String TITLE = "unauthorized";

    public AuthenticationError(String message) {
        this(message, Map.of());
    }

    public AuthenticationError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
