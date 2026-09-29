package dev.synapse.billing.dto;

/** {@code GET /v1/billing/portal-url}; {@code null} when the provider has no portal. */
public record PortalUrlResponse(String url) {}
