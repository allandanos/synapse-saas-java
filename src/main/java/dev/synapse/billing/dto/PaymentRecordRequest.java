package dev.synapse.billing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Operator payment record: an amount in minor units (never zero) and an optional external reference. */
public record PaymentRecordRequest(@NotNull @Min(1) Long amountCents, String reference) {}
