package dev.synapse.core.errors;

import java.util.Map;

/** The requested organization slug is reserved or invalid. */
public final class SlugUnavailableError extends DomainError {
    public static final int STATUS = 409;
    public static final String TITLE = "slug_unavailable";

    public SlugUnavailableError(String message) {
        this(message, Map.of());
    }

    public SlugUnavailableError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
