package dev.synapse.core.errors;

import java.util.Map;

/** Wrong email or password. */
public final class InvalidCredentialsError extends DomainError {
    public static final int STATUS = 401;
    public static final String TITLE = "invalid_credentials";

    public InvalidCredentialsError(String message) {
        this(message, Map.of());
    }

    public InvalidCredentialsError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
