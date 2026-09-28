package dev.synapse.core.errors;

import java.util.Map;

/** A uniqueness rule was violated. */
public final class ConflictError extends DomainError {
    public static final int STATUS = 409;
    public static final String TITLE = "conflict";

    public ConflictError(String message) {
        this(message, Map.of());
    }

    public ConflictError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
