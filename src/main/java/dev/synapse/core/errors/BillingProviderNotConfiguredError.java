package dev.synapse.core.errors;

import java.util.Map;

/** The selected billing provider lacks its credentials. */
public final class BillingProviderNotConfiguredError extends DomainError {
    public static final int STATUS = 409;
    public static final String TITLE = "billing_provider_not_configured";

    public BillingProviderNotConfiguredError(String message) {
        this(message, Map.of());
    }

    public BillingProviderNotConfiguredError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
