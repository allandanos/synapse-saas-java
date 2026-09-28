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
 *       {@code make plans-sync} (run with {@code --server.port=0} beside a live server).</li>
 *   <li>otherwise, when {@code synapse.auto-sync-plans} is on: sync at every
 *       boot; a failure is logged and only fatal in production.</li>
 * </ul>
 */
@Component
@Order(3)
public class PlanCatalogSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlanCatalogSyncRunner.class);
    public static final String COMMAND_OPTION = "plans-sync";

    private final PlanCatalogLoader loader;
    private final PlanCatalogSync sync;
    private final SynapseProperties props;
    private final ConfigurableApplicationContext context;

    public PlanCatalogSyncRunner(PlanCatalogLoader loader, PlanCatalogSync sync, SynapseProperties props, ConfigurableApplicationContext context) {
        this.loader = loader;
        this.sync = sync;
        this.props = props;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption(COMMAND_OPTION)) {
            PlanCatalogSync.SyncResult result = sync.sync(loader.load());
            System.out.println("features +" + result.featuresAdded() + ", metrics +" + result.metricsAdded() + ", plans +"
                + result.plansAdded() + "/~" + result.plansUpdated() + "/archived " + result.plansArchived());
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
