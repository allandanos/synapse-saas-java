package dev.synapse.billing.dto;

import dev.synapse.billing.invoicing.Invoice;
import java.time.Instant;
import java.util.UUID;

/** {@code InvoiceRead} — the contract's invoice projection (no provider ids, no pdf_url). */
public record InvoiceRead(UUID id, String number, String currency, long subtotalCents, long taxCents, long totalCents, String status,
                          Instant periodStart, Instant periodEnd, String hostedUrl, Instant issuedAt, Instant paidAt, Instant createdAt) {

    public static InvoiceRead from(Invoice invoice) {
        return new InvoiceRead(invoice.id(), invoice.number(), invoice.currency(), invoice.subtotalCents(), invoice.taxCents(),
            invoice.totalCents(), invoice.status(), invoice.periodStart(), invoice.periodEnd(), invoice.hostedUrl(), invoice.issuedAt(),
            invoice.paidAt(), invoice.createdAt());
    }
}
