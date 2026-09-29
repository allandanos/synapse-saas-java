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
    // ── Worker
    @DefaultValue("true") boolean workerEnabled,
    @Min(1) @DefaultValue("365") int auditRetentionDays,
    @Min(1) @DefaultValue("900") int storagePresignSeconds
) {
    public static final String DEV_SECRET_PREFIX = "dev-only-";

    public SynapseProperties {
        // Production guardrail (reference: Settings._production_guardrails)
        if ("production".equals(env) && secretKey != null && secretKey.startsWith(DEV_SECRET_PREFIX)) {
            throw new IllegalStateException("Refusing to start in production: SYNAPSE_SECRET_KEY is the dev default");
        }
        providerApiBase = providerApiBase == null ? Map.of() : Map.copyOf(providerApiBase);
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
