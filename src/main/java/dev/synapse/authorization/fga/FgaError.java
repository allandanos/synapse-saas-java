package dev.synapse.authorization.fga;

import dev.synapse.core.errors.DomainError;
import java.util.Map;

/** OpenFGA was unreachable or answered non-2xx (reference: {@code authorization/fga.py:FgaError}). */
public final class FgaError extends DomainError {
    public static final int STATUS = 503;
    public static final String TITLE = "authorization_backend_unavailable";

    public FgaError(String message) {
        this(message, Map.of());
    }

    public FgaError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }

    /** The response body OpenFGA returned, as far as it was captured. */
    public String body() {
        Object body = extras().get("body");
        return body == null ? "" : String.valueOf(body);
    }
}
