package dev.synapse.worker.jobs;

import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxRepository;
import dev.synapse.entitlements.EntitlementCache;
import dev.synapse.worker.Job;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mark lapsed grants revoked so entitlements stop resolving them, emit
 * {@code entitlement.expired}, and invalidate the affected orgs' caches AFTER
 * the commit — so no reader can cache the pre-revocation rows.
 */
@Component
public class ExpireEntitlementsJob implements Job {

    public static final String NAME = "expire_entitlements";

    private record Lapsed(UUID id, UUID organizationId, String featureKey) {}

    private final JdbcClient jdbc;
    private final OutboxRepository outbox;
    private final EntitlementCache cache;
    private final TransactionTemplate transaction;

    public ExpireEntitlementsJob(JdbcClient jdbc, OutboxRepository outbox, EntitlementCache cache,
                                 PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.cache = cache;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int runOnce() {
        Set<UUID> touched = new LinkedHashSet<>();
        int expired = transaction.execute(status -> {
            List<Lapsed> rows = jdbc.sql("""
                    SELECT id, organization_id, feature_key FROM entitlements
                    WHERE revoked_at IS NULL AND ends_at IS NOT NULL AND ends_at <= now()
                    """)
                .query((rs, i) -> new Lapsed(dev.synapse.core.db.Rows.uuid(rs, "id"),
                    dev.synapse.core.db.Rows.uuid(rs, "organization_id"), rs.getString("feature_key")))
                .list();
            for (Lapsed row : rows) {
                jdbc.sql("UPDATE entitlements SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
                    .param("id", row.id()).param("now", dev.synapse.core.db.Rows.at(Instant.now())).update();
                touched.add(row.organizationId());
                outbox.append(Events.ENTITLEMENT_EXPIRED, "entitlement", row.id(), row.organizationId(),
                    Map.of("feature_key", row.featureKey()));
            }
            return rows.size();
        });
        touched.forEach(cache::invalidate);
        return expired;
    }
}
