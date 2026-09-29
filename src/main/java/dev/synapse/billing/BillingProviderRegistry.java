package dev.synapse.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.billing.providers.ManualBillingProvider;
import dev.synapse.billing.providers.PaddleBillingProvider;
import dev.synapse.billing.providers.PayMongoBillingProvider;
import dev.synapse.billing.providers.StripeBillingProvider;
import dev.synapse.billing.providers.XenditBillingProvider;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.errors.BillingProviderNotConfiguredError;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Provider factory (reference: {@code billing/registry.py:build_provider}): the
 * configured provider, refusing a hosted one whose secret setting is empty.
 *
 * <p>{@code synapse.provider-api-base.<name>} overrides a provider's API base so
 * a test can point it at a local stub server; production never sets it.
 */
@Component
public class BillingProviderRegistry {

    /** Every provider the framework exposes, in the reference's registry order. */
    public static final List<String> PROVIDER_NAMES =
        List.of(ManualBillingProvider.NAME, StripeBillingProvider.NAME, XenditBillingProvider.NAME,
            PayMongoBillingProvider.NAME, PaddleBillingProvider.NAME);

    /** The capability table, without building (and therefore configuring) a provider. */
    public static final Map<String, Set<BillingCapability>> CAPABILITIES = Map.of(
        ManualBillingProvider.NAME, ManualBillingProvider.SUPPORTS,
        StripeBillingProvider.NAME, StripeBillingProvider.SUPPORTS,
        XenditBillingProvider.NAME, XenditBillingProvider.SUPPORTS,
        PayMongoBillingProvider.NAME, PayMongoBillingProvider.SUPPORTS,
        PaddleBillingProvider.NAME, PaddleBillingProvider.SUPPORTS);

    private final SynapseProperties props;
    private final ProviderHttp http;
    private final ObjectMapper json;

    public BillingProviderRegistry(SynapseProperties props, ProviderHttp http, ObjectMapper json) {
        this.props = props;
        this.http = http;
        this.json = json;
    }

    /** The provider {@code SYNAPSE_BILLING_PROVIDER} names. */
    public BillingProvider current() {
        return byName(props.billingProvider());
    }

    /** For webhook ingest: build the provider the event claims to come from. */
    public BillingProvider byName(String name) {
        return switch (name) {
            case ManualBillingProvider.NAME -> new ManualBillingProvider(json, props.manualWebhookToken(), props.billingCurrency());
            case StripeBillingProvider.NAME -> new StripeBillingProvider(http, json,
                required(props.stripeSecretKey(), "Stripe", "SYNAPSE_STRIPE_SECRET_KEY"), props.stripeWebhookSecret(),
                apiBase(StripeBillingProvider.NAME));
            case XenditBillingProvider.NAME -> new XenditBillingProvider(http, json,
                required(props.xenditSecretKey(), "Xendit", "SYNAPSE_XENDIT_SECRET_KEY"), props.xenditWebhookToken(),
                props.billingCurrency(), apiBase(XenditBillingProvider.NAME));
            case PayMongoBillingProvider.NAME -> new PayMongoBillingProvider(http, json,
                required(props.paymongoSecretKey(), "PayMongo", "SYNAPSE_PAYMONGO_SECRET_KEY"), props.paymongoWebhookSecret(),
                props.billingCurrency(), apiBase(PayMongoBillingProvider.NAME));
            case PaddleBillingProvider.NAME -> new PaddleBillingProvider(http, json,
                required(props.paddleSecretKey(), "Paddle", "SYNAPSE_PADDLE_SECRET_KEY"), props.paddleWebhookSecret(),
                apiBase(PaddleBillingProvider.NAME));
            default -> throw new BillingProviderNotConfiguredError("Unknown billing provider '" + name + "'");
        };
    }

    /**
     * Providers whose recurring charges WE issue (no {@code recurring_hosted});
     * the worker's renewal job bills exactly these. Hosted providers renew on
     * their side and report through webhooks.
     */
    public static List<String> locallyBilledProviderNames() {
        return PROVIDER_NAMES.stream().filter(name -> !CAPABILITIES.get(name).contains(BillingCapability.RECURRING_HOSTED)).toList();
    }

    private String apiBase(String provider) {
        return props.providerApiBase().get(provider);
    }

    private static String required(String secret, String label, String variable) {
        if (secret == null || secret.isBlank()) {
            throw new BillingProviderNotConfiguredError(label + " is selected but " + variable + " is not set");
        }
        return secret;
    }
}
