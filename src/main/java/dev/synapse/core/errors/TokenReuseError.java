package dev.synapse.core.errors;

import java.util.Map;

/** A rotated refresh token was replayed — possible theft. The chain is revoked. */
public final class TokenReuseError extends DomainError {
    public static final int STATUS = 401;
    public static final String TITLE = "token_reuse_detected";

    public TokenReuseError(String message) {
        this(message, Map.of());
    }

    public TokenReuseError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
