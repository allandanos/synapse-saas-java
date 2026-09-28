package dev.synapse.core.errors;

import java.util.Map;

/** Registration with an email that already has an account. */
public final class EmailAlreadyRegisteredError extends DomainError {
    public static final int STATUS = 409;
    public static final String TITLE = "email_already_registered";

    public EmailAlreadyRegisteredError(String message) {
        this(message, Map.of());
    }

    public EmailAlreadyRegisteredError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
