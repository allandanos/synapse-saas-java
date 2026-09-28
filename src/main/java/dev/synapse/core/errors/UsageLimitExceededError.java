package dev.synapse.core.errors;

import java.util.Map;

/** A consume/gauge/seat write would cross the effective limit: {@code metric}, {@code limit}, {@code used}, {@code upgrade_url}. */
public final class UsageLimitExceededError extends DomainError {
    public static final int STATUS = 402;
    public static final String TITLE = "usage_limit_exceeded";

    public UsageLimitExceededError(String message) {
        this(message, Map.of());
    }

    public UsageLimitExceededError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
