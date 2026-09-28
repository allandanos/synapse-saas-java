package dev.synapse.billing;

import java.util.Set;

/**
 * The default provider: no external account, we bill locally (invoices in
 * arrears, manual payment recording by the operator). Carries
 * {@code client_confirm} because there is no payment truth of its own.
 */
public final class ManualBillingProvider implements BillingProvider {

    public static final String NAME = "manual";

    private final String currency;

    public ManualBillingProvider(String currency) {
        this.currency = currency;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<BillingCapability> supports() {
        return Set.of(BillingCapability.HOSTED_CHECKOUT, BillingCapability.CLIENT_CONFIRM);
    }

    public String currency() {
        return currency;
    }

    @Override
    public SubscriptionRef changePlan(String providerSubscriptionId, ChangePlanRequest request) {
        throw new UnsupportedOperationException("manual bills locally: plan changes never reach a provider");
    }
}
