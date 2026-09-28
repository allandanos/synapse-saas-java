package dev.synapse.core.errors;

import java.util.Map;

/** Invite token unknown, expired or already used. */
public final class InviteNotFoundError extends DomainError {
    public static final int STATUS = 404;
    public static final String TITLE = "invite_not_found";

    public InviteNotFoundError(String message) {
        this(message, Map.of());
    }

    public InviteNotFoundError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
