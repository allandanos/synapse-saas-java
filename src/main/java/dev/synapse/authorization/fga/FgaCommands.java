package dev.synapse.authorization.fga;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reference's {@code synapse-cli authz fga …} as jar options
 * ({@code make authz-fga-write-model|authz-fga-sync|authz-fga-check}):
 *
 * <ul>
 *   <li>{@code --authz-fga-write-model [--create-store=NAME] [--dsl]} — write the
 *       catalog-generated model to the store (or print it as DSL);</li>
 *   <li>{@code --authz-fga-sync --all | --org=ID} — converge tuples to the RBAC
 *       state (backfill or repair);</li>
 *   <li>{@code --authz-fga-check=USER,ORG,PERMISSION} — ask the store directly.</li>
 * </ul>
 */
@Component
@Order(5)
public class FgaCommands implements ApplicationRunner {

    public static final String WRITE_MODEL_OPTION = "authz-fga-write-model";
    public static final String SYNC_OPTION = "authz-fga-sync";
    public static final String CHECK_OPTION = "authz-fga-check";
    public static final String CREATE_STORE_OPTION = "create-store";
    public static final String DSL_OPTION = "dsl";
    public static final String ALL_OPTION = "all";
    public static final String ORG_OPTION = "org";

    private final FgaClient client;
    private final TupleSync sync;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ConfigurableApplicationContext context;

    public FgaCommands(FgaClient client, TupleSync sync, JdbcClient jdbc, TransactionTemplate tx,
                       ConfigurableApplicationContext context) {
        this.client = client;
        this.sync = sync;
        this.jdbc = jdbc;
        this.tx = tx;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption(WRITE_MODEL_OPTION)) {
            exit(writeModel(args));
        } else if (args.containsOption(SYNC_OPTION)) {
            exit(syncTuples(args));
        } else if (args.containsOption(CHECK_OPTION)) {
            exit(check(first(args, CHECK_OPTION)));
        }
    }

    private int writeModel(ApplicationArguments args) {
        if (args.containsOption(DSL_OPTION)) {
            System.out.println(FgaModel.renderDsl());
            return 0;
        }
        String storeName = first(args, CREATE_STORE_OPTION);
        if (storeName != null && !storeName.isEmpty()) {
            client.storeId(client.createStore(storeName));
        }
        String modelId = client.writeModel(FgaModel.build());
        System.out.println("store_id=" + client.storeId());
        System.out.println("authorization_model_id=" + modelId);
        System.out.println("Set SYNAPSE_OPENFGA_STORE_ID / SYNAPSE_OPENFGA_MODEL_ID accordingly.");
        return 0;
    }

    private int syncTuples(ApplicationArguments args) {
        String orgId = first(args, ORG_OPTION);
        boolean all = args.containsOption(ALL_OPTION);
        if (!all && (orgId == null || orgId.isEmpty())) {
            System.err.println("Pass --all or --org=<id>");
            return 2;
        }
        List<Map<String, Object>> pairs = tx.execute(status -> jdbc
            .sql("SELECT organization_id, user_id FROM memberships WHERE user_id IS NOT NULL"
                + (orgId == null || orgId.isEmpty() ? "" : " AND organization_id = CAST(:org AS uuid)"))
            .params(orgId == null || orgId.isEmpty() ? Map.of() : Map.of("org", orgId))
            .query((rs, i) -> Map.<String, Object>of(
                "organization_id", rs.getString("organization_id"), "user_id", rs.getString("user_id")))
            .list());
        pairs.forEach(sync::apply);
        System.out.println("synced " + pairs.size() + " membership(s)");
        return 0;
    }

    private int check(String argument) {
        String[] parts = argument == null ? new String[0] : argument.split(",");
        if (parts.length != 3) {
            System.err.println("Usage: --authz-fga-check=<user id>,<organization id>,<permission>");
            return 2;
        }
        boolean allowed = client.check(FgaTuple.userObject(UUID.fromString(parts[0].trim())),
            FgaModel.relationFor(parts[2].trim()), FgaTuple.organizationObject(UUID.fromString(parts[1].trim())));
        System.out.println(allowed ? "allowed" : "denied");
        return allowed ? 0 : 1;
    }

    private static String first(ApplicationArguments args, String option) {
        List<String> values = args.getOptionValues(option);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private void exit(int status) {
        System.exit(SpringApplication.exit(context, () -> status));
    }
}
