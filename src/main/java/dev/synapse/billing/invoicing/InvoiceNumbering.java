package dev.synapse.billing.invoicing;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * {@code INV-YYYYMM-####} scoped per org (reference: {@code InvoicingService._next_number}).
 *
 * <p>Not a gapless sequence — gapless legal numbering is a jurisdiction concern;
 * this is unique (uq_invoices_org_number), sortable and auditable.
 */
public final class InvoiceNumbering {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);

    private InvoiceNumbering() {}

    /** {@code priorNumbered} is the count of already-numbered invoices for the org. */
    public static String next(long priorNumbered, Instant now) {
        return "INV-" + STAMP.format(now) + "-" + String.format("%04d", priorNumbered + 1);
    }
}
