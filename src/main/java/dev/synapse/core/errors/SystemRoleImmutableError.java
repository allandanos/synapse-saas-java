package dev.synapse.core.errors;

import java.util.Map;

/** System roles cannot be modified. */
public final class SystemRoleImmutableError extends DomainError {
    public static final int STATUS = 409;
    public static final String TITLE = "system_role_immutable";

    public SystemRoleImmutableError(String message) {
        this(message, Map.of());
    }

    public SystemRoleImmutableError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
