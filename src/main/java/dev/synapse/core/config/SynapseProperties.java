package dev.synapse.core.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashSet;
import java.util.List;
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
    @DefaultValue("") String paddleSecretKey,
    @DefaultValue("") String xenditSecretKey,
    @DefaultValue("") String paymongoSecretKey
) {
    public static final String DEV_SECRET_PREFIX = "dev-only-";

    public SynapseProperties {
        // Production guardrail (reference: Settings._production_guardrails)
        if ("production".equals(env) && secretKey != null && secretKey.startsWith(DEV_SECRET_PREFIX)) {
            throw new IllegalStateException("Refusing to start in production: SYNAPSE_SECRET_KEY is the dev default");
        }
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
