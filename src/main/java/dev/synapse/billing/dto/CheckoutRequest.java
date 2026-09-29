package dev.synapse.billing.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /v1/billing/checkout} and {@code /checkout/confirm}. */
public record CheckoutRequest(@NotBlank String planKey) {}
