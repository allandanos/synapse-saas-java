package dev.synapse.core.errors;

import java.util.Map;

/** The provider's API refused or failed — a bad gateway, not the tenant's fault. */
public final class BillingProviderError extends DomainError {
    public static final int STATUS = 502;
    public static final String TITLE = "billing_provider_error";

    public BillingProviderError(String message) {
        this(message, Map.of());
    }

    public BillingProviderError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
