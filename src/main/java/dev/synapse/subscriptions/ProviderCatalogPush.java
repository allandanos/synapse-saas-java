package dev.synapse.subscriptions;

import dev.synapse.billing.BillingCapability;
import dev.synapse.billing.BillingProvider;
import dev.synapse.billing.BillingProviderRegistry;
import dev.synapse.billing.providers.StripeBillingProvider;
import dev.synapse.core.db.Json;
import dev.synapse.subscriptions.PlanCatalog.PlanDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Push the plan catalog to a billing provider (reference:
 * {@code cli.py:_push_provider_catalog} + {@code stripe_provider.upsert_product_and_price}).
 *
 * <p>Custom-priced plans have nothing to push. A dry run needs no credentials —
 * it only describes the diff; {@code --apply} creates the product and price and
 * records the returned ids under {@code plans.provider_refs[<provider>]}.
 */
@Component
public class ProviderCatalogPush {

    private final BillingProviderRegistry providers;
    private final PlanRepository plans;
    private final Json json;

    public ProviderCatalogPush(BillingProviderRegistry providers, PlanRepository plans, Json json) {
        this.providers = providers;
        this.plans = plans;
        this.json = json;
    }

    /** The lines the CLI prints, in catalog order. */
    @Transactional
    public List<String> push(String providerName, PlanCatalog catalog, boolean apply) {
        List<String> lines = new ArrayList<>();
        BillingProvider provider = null;
        for (PlanDefinition definition : catalog.plans()) {
            if (definition.priceCents() == null) {
                continue; // custom-priced plans have nothing to push
            }
            if (!apply) {
                lines.add("[dry-run] " + providerName + ": upsert product+price for "
                    + definition.key() + " (" + definition.priceCents() + " minor units)");
                continue;
            }
            if (provider == null) {
                provider = providers.byName(providerName);
                if (!BillingProviderRegistry.CAPABILITIES.get(providerName).contains(BillingCapability.PLAN_SYNC)) {
                    lines.add(providerName + " does not support plan sync; skipping");
                    return lines;
                }
            }
            Map<String, String> refs = ((StripeBillingProvider) provider).upsertProductAndPrice(
                definition.key(), definition.name(), definition.priceCents(),
                definition.currency() != null ? definition.currency() : catalog.defaults().currency(),
                definition.interval() != null ? definition.interval() : catalog.defaults().interval());
            Plan plan = plans.findByKey(definition.key(), true).orElseThrow();
            plans.mergeProviderRefs(plan.id(), providerName, json.write(refs));
            lines.add(providerName + ": " + definition.key() + " → " + refs);
        }
        return lines;
    }
}
