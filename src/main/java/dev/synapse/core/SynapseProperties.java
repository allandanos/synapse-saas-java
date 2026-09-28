package dev.synapse.core;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code SYNAPSE_*} settings the contract exposes through {@code GET /v1/meta}.
 * Same names as the reference implementation so one {@code .env} drives both.
 */
@Validated
@ConfigurationProperties(prefix = "synapse")
public record SynapseProperties(
    @NotBlank String version,
    @Pattern(regexp = "manual|stripe|paddle|xendit|paymongo") String billingProvider,
    @Pattern(regexp = "local|keycloak") String identityProvider,
    @Pattern(regexp = "app|app_and_rls") String tenantIsolation
) {}
