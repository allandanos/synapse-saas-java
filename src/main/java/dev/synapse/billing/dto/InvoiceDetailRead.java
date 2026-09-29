package dev.synapse.billing.dto;

import dev.synapse.billing.invoicing.Invoice;
import dev.synapse.billing.invoicing.InvoiceLine;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code InvoiceDetailRead} = the {@link InvoiceRead} fields plus {@code lines[]}.
 * Flattened rather than nested: the contract's schema inherits, it does not compose.
 */
public record InvoiceDetailRead(UUID id, String number, String currency, long subtotalCents, long taxCents, long totalCents, String status,
                                Instant periodStart, Instant periodEnd, String hostedUrl, Instant issuedAt, Instant paidAt, Instant createdAt,
                                List<InvoiceLineRead> lines) {

    public static InvoiceDetailRead of(Invoice invoice, List<InvoiceLine> lines) {
        return new InvoiceDetailRead(invoice.id(), invoice.number(), invoice.currency(), invoice.subtotalCents(), invoice.taxCents(),
            invoice.totalCents(), invoice.status(), invoice.periodStart(), invoice.periodEnd(), invoice.hostedUrl(), invoice.issuedAt(),
            invoice.paidAt(), invoice.createdAt(), lines.stream().map(InvoiceLineRead::from).toList());
    }
}
