package dev.synapse.core.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code SYNAPSE_*} settings. Same names and defaults as the reference's
 * {@code core/config.py} wherever the concept exists, so one {@code .env} drives both.
 */
@Validated
@ConfigurationProperties(prefix = "synapse")
public record SynapseProperties(
    @NotBlank String version,
    @DefaultValue("development") String env,
    @Pattern(regexp = "manual|stripe|paddle|xendit|paymongo") String billingProvider,
    @Pattern(regexp = "local|keycloak") String identityProvider,
    @Pattern(regexp = "app|app_and_rls") String tenantIsolation,
    @NotBlank String secretKey,
    @Min(1) @DefaultValue("15") int accessTokenTtlMinutes,
    @Min(1) @DefaultValue("30") int refreshTokenTtlDays,
    @Min(0) @DefaultValue("10") int refreshReuseGraceSeconds,
    @DefaultValue("http://localhost:3000") String webOrigin,
    @DefaultValue("") List<String> webOrigins,
    Boolean cookieSecure,
    @DefaultValue("") String bootstrapAdminEmail,
    @DefaultValue("") String bootstrapAdminPassword,
    // ── Plans / catalog (reference: plans_file, auto_sync_plans, default_plan_key, grace_on_past_due)
    @DefaultValue("classpath:config/plans.yaml") String plansFile,
    @DefaultValue("true") boolean autoSyncPlans,
    @DefaultValue("free") String defaultPlanKey,
    @DefaultValue("true") boolean graceOnPastDue,
    // ── Billing
    @DefaultValue("PHP") String billingCurrency,
    @DefaultValue("") String stripeSecretKey,
    @DefaultValue("") String stripeWebhookSecret,
    @DefaultValue("") String paddleSecretKey,
    @DefaultValue("") String paddleWebhookSecret,
    @DefaultValue("") String xenditSecretKey,
    @DefaultValue("") String xenditWebhookToken,
    @DefaultValue("") String paymongoSecretKey,
    @DefaultValue("") String paymongoWebhookSecret,
    @DefaultValue("") String manualWebhookToken,
    /** Test-only: {@code synapse.provider-api-base.<name>} points a provider at a stub server. */
    Map<String, String> providerApiBase,
    @DefaultValue("") String manualPayToInstructions,
    // ── Notifications (reference: notifier, smtp_*)
    @Pattern(regexp = "smtp|noop") @DefaultValue("smtp") String notifier,
    @DefaultValue("") String smtpHost,
    @Min(1) @DefaultValue("1025") int smtpPort,
    @DefaultValue("synapse@localhost") String smtpFrom,
    @DefaultValue("") String smtpUsername,
    @DefaultValue("") String smtpPassword,
    @Pattern(regexp = "none|starttls|ssl") @DefaultValue("none") String smtpTls,
    // ── Storage (S3-compatible; unset bucket ⇒ local disk under storageRoot)
    @DefaultValue("") String s3EndpointUrl,
    @DefaultValue("us-east-1") String s3Region,
    @DefaultValue("") String s3Bucket,
    @DefaultValue("") String s3AccessKeyId,
    @DefaultValue("") String s3SecretAccessKey,
    @DefaultValue(".storage") String storageRoot,
    // ── Worker
    @DefaultValue("true") boolean workerEnabled,
    @Min(1) @DefaultValue("365") int auditRetentionDays,
    @Min(1) @DefaultValue("3600") int storagePresignSeconds,
    // ── Redis (reference: redis_url). Empty ⇒ the caches, the auth rate limiter
    // and the OIDC login state stay per-process; /readyz reports not_configured.
    @DefaultValue("") String redisUrl,
    // ── Rate limiting (reference: auth_rate_limit_per_ip/_per_identity, auth_rate_window_seconds)
    @Min(1) @DefaultValue("20") int authRateLimitPerIp,
    @Min(1) @DefaultValue("5") int authRateLimitPerIdentity,
    @Min(1) @DefaultValue("60") int authRateWindowSeconds,
    /** CIDRs whose X-Forwarded-For is honoured (reference: trusted_proxies). */
    @DefaultValue("") List<String> trustedProxies,
    // ── Authorization backend (ADR 0009)
    @Pattern(regexp = "rbac|openfga") @DefaultValue("rbac") String authzBackend,
    @DefaultValue("") String openfgaUrl,
    @DefaultValue("") String openfgaStoreId,
    /** Empty ⇒ the store's latest model. */
    @DefaultValue("") String openfgaModelId,
    @DefaultValue("") String openfgaApiToken,
    @Pattern(regexp = "closed|rbac") @DefaultValue("closed") String openfgaFailMode,
    // ── OIDC / Keycloak (ADR 0010)
    @DefaultValue("") String keycloakBaseUrl,
    @DefaultValue("") String keycloakRealm,
    @DefaultValue("") String keycloakClientId,
    @DefaultValue("") String keycloakClientSecret,
    @DefaultValue("false") boolean keycloakAllowPasswordGrant,
    /** Overrides the callback URL sent to the IdP (reference: oidc_redirect_uri). */
    @DefaultValue("") String oidcRedirectUri
) {
    public static final String DEV_SECRET_PREFIX = "dev-only-";

    /** Hard ceilings for production: dev and e2e raise the auth limits to register
     *  many users from one IP; a baked .env must not carry that into production. */
    public static final int PRODUCTION_MAX_AUTH_PER_IP = 100;
    public static final int PRODUCTION_MAX_AUTH_PER_IDENTITY = 20;

    public SynapseProperties {
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        for (String cidr : trustedProxies) {
            Cidr.parse(cidr); // rejects garbage at boot, like the reference's _validate_cidrs
        }
        // Production guardrails (reference: Settings._production_guardrails)
        if ("production".equals(env)) {
            List<String> problems = new java.util.ArrayList<>();
            if (authRateLimitPerIp > PRODUCTION_MAX_AUTH_PER_IP) {
                problems.add("SYNAPSE_AUTH_RATE_LIMIT_PER_IP=" + authRateLimitPerIp
                    + " exceeds the production ceiling of " + PRODUCTION_MAX_AUTH_PER_IP);
            }
            if (authRateLimitPerIdentity > PRODUCTION_MAX_AUTH_PER_IDENTITY) {
                problems.add("SYNAPSE_AUTH_RATE_LIMIT_PER_IDENTITY=" + authRateLimitPerIdentity
                    + " exceeds the production ceiling of " + PRODUCTION_MAX_AUTH_PER_IDENTITY);
            }
            if (secretKey != null && secretKey.startsWith(DEV_SECRET_PREFIX)) {
                problems.add("SYNAPSE_SECRET_KEY is the dev default");
            }
            if (!problems.isEmpty()) {
                throw new IllegalStateException("Refusing to start in production: " + String.join("; ", problems));
            }
        }
        providerApiBase = providerApiBase == null ? Map.of() : Map.copyOf(providerApiBase);
    }

    /** True when permission checks ask OpenFGA instead of the RBAC tables. */
    public boolean openfgaBackend() {
        return "openfga".equals(authzBackend);
    }

    /** True when browser logins go through Keycloak (reference: identity_provider). */
    public boolean keycloakIdentity() {
        return "keycloak".equals(identityProvider);
    }

    public boolean isProduction() {
        return "production".equals(env);
    }

    /** {@code app_and_rls}: the three RLS GUCs are bound on every transaction. */
    public boolean rlsEnabled() {
        return "app_and_rls".equals(tenantIsolation);
    }

    public int accessTokenTtlSeconds() {
        return accessTokenTtlMinutes * 60;
    }

    public long refreshTokenTtlSeconds() {
        return refreshTokenTtlDays * 86_400L;
    }

    /** Refresh-cookie Secure flag: explicit setting, else derived (https origin or production). */
    public boolean cookieSecureEffective() {
        if (cookieSecure != null) {
            return cookieSecure;
        }
        return isProduction() || webOrigin.toLowerCase().startsWith("https://");
    }

    /** {@code web_origin} first, then the extras, de-duplicated and order-preserving. */
    public List<String> corsOrigins() {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        seen.add(webOrigin);
        webOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).forEach(seen::add);
        return List.copyOf(seen);
    }
}
