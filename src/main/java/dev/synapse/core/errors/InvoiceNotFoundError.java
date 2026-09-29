package dev.synapse.core.errors;

import java.util.Map;

/** Invoice missing — or belonging to another tenant (cross-tenant reads are 404, never 403). */
public final class InvoiceNotFoundError extends DomainError {
    public static final int STATUS = 404;
    public static final String TITLE = "invoice_not_found";

    public InvoiceNotFoundError(String message) {
        this(message, Map.of());
    }

    public InvoiceNotFoundError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
