package dev.synapse.billing.invoicing;

import java.util.Map;
import java.util.UUID;

/**
 * A single billable line of a framework-generated invoice
 * ({@code invoice_lines}; kind ∈ plan|overage|credit|custom).
 * {@code quantity × unitAmountCents == amountCents} always holds, so a line reconciles on its own.
 */
public record InvoiceLine(UUID id, UUID invoiceId, UUID organizationId, String kind, String description, long quantity,
                          long unitAmountCents, long amountCents, String metric, Map<String, Object> properties) {

    public InvoiceLine {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    /** A line about to be inserted (no id/invoice yet). */
    public record Draft(String kind, String description, long quantity, long unitAmountCents, long amountCents, String metric,
                        Map<String, Object> properties) {

        public Draft {
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }

        public static Draft of(String kind, String description, long quantity, long unitAmountCents, long amountCents) {
            return new Draft(kind, description, quantity, unitAmountCents, amountCents, null, Map.of());
        }
    }
}
