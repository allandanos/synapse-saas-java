package dev.synapse.billing;

import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.errors.BillingProviderNotConfiguredError;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Provider factory (reference: {@code billing/registry.py:build_provider}):
 * the configured provider, refusing a hosted one whose secret is missing.
 */
@Component
public class BillingProviderRegistry {

    private final SynapseProperties props;

    public BillingProviderRegistry(SynapseProperties props) {
        this.props = props;
    }

    /** The provider {@code SYNAPSE_BILLING_PROVIDER} names. */
    public BillingProvider current() {
        return byName(props.billingProvider());
    }

    public BillingProvider byName(String name) {
        return switch (name) {
            case ManualBillingProvider.NAME -> new ManualBillingProvider(props.billingCurrency());
            case "stripe" -> configured(HostedBillingProvider.STRIPE, props.stripeSecretKey(), "Stripe", "SYNAPSE_STRIPE_SECRET_KEY");
            case "xendit" -> configured(HostedBillingProvider.XENDIT, props.xenditSecretKey(), "Xendit", "SYNAPSE_XENDIT_SECRET_KEY");
            case "paymongo" -> configured(HostedBillingProvider.PAYMONGO, props.paymongoSecretKey(), "PayMongo", "SYNAPSE_PAYMONGO_SECRET_KEY");
            case "paddle" -> configured(HostedBillingProvider.PADDLE, props.paddleSecretKey(), "Paddle", "SYNAPSE_PADDLE_SECRET_KEY");
            default -> throw new BillingProviderNotConfiguredError("Unknown billing provider '" + name + "'");
        };
    }

    /** Providers whose recurring charges WE issue (no {@code recurring_hosted}); the milestone-4 renewal job bills exactly these. */
    public static List<String> locallyBilledProviderNames() {
        return List.of(new ManualBillingProvider("PHP"), HostedBillingProvider.STRIPE, HostedBillingProvider.PADDLE,
                HostedBillingProvider.XENDIT, HostedBillingProvider.PAYMONGO).stream()
            .filter(p -> !p.supports().contains(BillingCapability.RECURRING_HOSTED))
            .map(BillingProvider::name)
            .toList();
    }

    private static BillingProvider configured(HostedBillingProvider provider, String secret, String label, String variable) {
        if (secret == null || secret.isBlank()) {
            throw new BillingProviderNotConfiguredError(label + " is selected but " + variable + " is not set");
        }
        return provider;
    }
}
