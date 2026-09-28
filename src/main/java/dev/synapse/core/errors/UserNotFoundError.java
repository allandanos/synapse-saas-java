package dev.synapse.core.errors;

import java.util.Map;

/** The referenced user row does not exist. */
public final class UserNotFoundError extends DomainError {
    public static final int STATUS = 404;
    public static final String TITLE = "user_not_found";

    public UserNotFoundError(String message) {
        this(message, Map.of());
    }

    public UserNotFoundError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
