package dev.synapse.billing.dto;

/** Hosted-checkout URL, or manual instructions for off-provider flows. */
public record CheckoutResponse(String url, String provider, String manualInstructions) {}
