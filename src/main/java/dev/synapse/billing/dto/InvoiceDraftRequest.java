package dev.synapse.billing.dto;

import dev.synapse.core.validation.Periods;
import jakarta.validation.constraints.Pattern;

/** {@code POST /v1/billing/invoices/draft} — the month to bill; absent ⇒ the current one. */
public record InvoiceDraftRequest(@Pattern(regexp = Periods.MONTH_PATTERN) String period) {}
