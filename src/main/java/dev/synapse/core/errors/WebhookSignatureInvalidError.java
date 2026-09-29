package dev.synapse.core.errors;

import java.util.Map;

/** Provider webhook whose signature/token is missing, malformed, stale or wrong. */
public final class WebhookSignatureInvalidError extends DomainError {
    public static final int STATUS = 400;
    public static final String TITLE = "webhook_signature_invalid";

    public WebhookSignatureInvalidError(String message) {
        this(message, Map.of());
    }

    public WebhookSignatureInvalidError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
