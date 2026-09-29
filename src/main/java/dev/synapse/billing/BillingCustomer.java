package dev.synapse.billing;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Row of {@code billing_customers} — one per organization, the bridge to the
 * provider's customer object. Provider ids are stored verbatim so
 * {@code (provider, provider_customer_id)} makes webhook application idempotent.
 */
public record BillingCustomer(UUID id, UUID organizationId, String provider, String providerCustomerId, String email, String name,
                              String taxId, String currency, LocalDateTime createdAt) {}
