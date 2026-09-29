package dev.synapse.billing.invoicing;

import java.time.Instant;
import java.util.UUID;

/**
 * Row of {@code invoices} (reference: {@code billing/models.py:Invoice}).
 * Immutable; {@link InvoiceRepository} persists the whole record.
 */
public record Invoice(UUID id, UUID organizationId, UUID billingCustomerId, String provider, String providerInvoiceId, String number,
                      String currency, long subtotalCents, long taxCents, long totalCents, String status, Instant periodStart,
                      Instant periodEnd, String hostedUrl, String pdfUrl, Instant issuedAt, Instant paidAt, Instant createdAt) {

    public Invoice withStatus(String newStatus) {
        return new Invoice(id, organizationId, billingCustomerId, provider, providerInvoiceId, number, currency, subtotalCents, taxCents,
            totalCents, newStatus, periodStart, periodEnd, hostedUrl, pdfUrl, issuedAt, paidAt, createdAt);
    }

    public Invoice withIssued(String newNumber, Instant at) {
        return new Invoice(id, organizationId, billingCustomerId, provider, providerInvoiceId, newNumber, currency, subtotalCents, taxCents,
            totalCents, status, periodStart, periodEnd, hostedUrl, pdfUrl, at, paidAt, createdAt);
    }

    public Invoice withPaidAt(Instant at) {
        return new Invoice(id, organizationId, billingCustomerId, provider, providerInvoiceId, number, currency, subtotalCents, taxCents,
            totalCents, status, periodStart, periodEnd, hostedUrl, pdfUrl, issuedAt, at, createdAt);
    }
}
