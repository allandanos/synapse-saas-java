package dev.synapse.core.errors;

import java.util.Map;

/** Request failed validation; the per-field list rides in {@code errors[]}. */
public final class ValidationFailedError extends DomainError {
    public static final int STATUS = 422;
    public static final String TITLE = "validation_failed";

    public ValidationFailedError(String message) {
        this(message, Map.of());
    }

    public ValidationFailedError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
