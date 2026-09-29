package dev.synapse.core.errors;

import java.util.LinkedHashMap;
import java.util.Map;

/** Too many attempts in the current window (reference: {@code core/errors.py:RateLimitedError}). */
public final class RateLimitedError extends DomainError {
    public static final int STATUS = 429;
    public static final String TITLE = "rate_limited";
    public static final String DETAIL = "Too many attempts; slow down and retry shortly";

    public RateLimitedError(long retryAfterSeconds, int limit) {
        super(STATUS, TITLE, DETAIL, extras(retryAfterSeconds, limit));
    }

    private static Map<String, Object> extras(long retryAfterSeconds, int limit) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("retry_after_seconds", Math.max(retryAfterSeconds, 1));
        extras.put("limit", limit);
        return extras;
    }

    public long retryAfterSeconds() {
        return ((Number) extras().get("retry_after_seconds")).longValue();
    }
}
