package dev.synapse.subscriptions;

import dev.synapse.core.config.SynapseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Startup catalog sync (reference: {@code api/app.py} lifespan with
 * {@code SYNAPSE_AUTO_SYNC_PLANS}, and {@code synapse-cli plans sync}).
 *
 * <ul>
 *   <li>{@code --plans-sync}: sync once, print the summary, exit 0 — the port's
 *       {@code make plans-sync} (run with {@code --server.port=0} beside a live server).
 *       Add {@code --stripe} (or {@code --provider=stripe}) to also push the
 *       catalog to the provider; {@code --apply} turns the dry run into real
 *       products and prices, recorded in {@code plans.provider_refs}.</li>
 *   <li>otherwise, when {@code synapse.auto-sync-plans} is on: sync at every
 *       boot; a failure is logged and only fatal in production.</li>
 * </ul>
 */
@Component
@Order(3)
public class PlanCatalogSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlanCatalogSyncRunner.class);
    public static final String COMMAND_OPTION = "plans-sync";
    public static final String PROVIDER_OPTION = "provider";
    public static final String APPLY_OPTION = "apply";

    private final PlanCatalogLoader loader;
    private final PlanCatalogSync sync;
    private final ProviderCatalogPush providerPush;
    private final SynapseProperties props;
    private final ConfigurableApplicationContext context;

    public PlanCatalogSyncRunner(PlanCatalogLoader loader, PlanCatalogSync sync, ProviderCatalogPush providerPush,
                                 SynapseProperties props, ConfigurableApplicationContext context) {
        this.loader = loader;
        this.sync = sync;
        this.providerPush = providerPush;
        this.props = props;
        this.context = context;
    }

    /** {@code --stripe} is shorthand for {@code --provider=stripe}; any provider name works. */
    static String providerName(ApplicationArguments args) {
        java.util.List<String> values = args.getOptionValues(PROVIDER_OPTION);
        if (values != null && !values.isEmpty()) {
            return values.get(0);
        }
        return dev.synapse.billing.BillingProviderRegistry.PROVIDER_NAMES.stream()
            .filter(args::containsOption).findFirst().orElse(null);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption(COMMAND_OPTION)) {
            PlanCatalog catalog = loader.load();
            PlanCatalogSync.SyncResult result = sync.sync(catalog);
            System.out.println("features +" + result.featuresAdded() + ", metrics +" + result.metricsAdded() + ", plans +"
                + result.plansAdded() + "/~" + result.plansUpdated() + "/archived " + result.plansArchived());
            String provider = providerName(args);
            if (provider != null) {
                providerPush.push(provider, catalog, args.containsOption(APPLY_OPTION)).forEach(System.out::println);
            }
            System.exit(SpringApplication.exit(context, () -> 0));
            return;
        }
        if (!props.autoSyncPlans()) {
            return;
        }
        try {
            PlanCatalogSync.SyncResult result = sync.sync(loader.load());
            log.info("plans_auto_synced {}", result.summary());
        } catch (RuntimeException e) {
            log.error("plans_auto_sync_failed error={}", e.toString(), e);
            if (props.isProduction()) {
                throw e;
            }
        }
    }
}
