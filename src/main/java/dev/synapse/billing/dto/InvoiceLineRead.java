package dev.synapse.billing.dto;

import dev.synapse.billing.invoicing.InvoiceLine;
import java.util.UUID;

/** {@code InvoiceLineRead}; {@code quantity × unitAmountCents == amountCents}. */
public record InvoiceLineRead(UUID id, String kind, String description, long quantity, long unitAmountCents, long amountCents, String metric) {

    public static InvoiceLineRead from(InvoiceLine line) {
        return new InvoiceLineRead(line.id(), line.kind(), line.description(), line.quantity(), line.unitAmountCents(),
            line.amountCents(), line.metric());
    }
}
