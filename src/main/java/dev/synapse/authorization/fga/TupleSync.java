package dev.synapse.authorization.fga;

import dev.synapse.authorization.PermissionCatalog;
import dev.synapse.core.cache.Caches;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.db.RlsGucs;
import dev.synapse.core.ids.Ids;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import dev.synapse.tenancy.Membership;
import dev.synapse.tenancy.MembershipRepository;
import dev.synapse.tenancy.MembershipView;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RBAC (the source of truth) → OpenFGA relationship tuples
 * (ADR 0009; reference: {@code authorization/sync.py}).
 *
 * <p>Request side: {@link #queue} appends an internal outbox event inside the
 * mutating transaction. Worker side: {@link #apply} recomputes the member's
 * desired tuples from the database and writes/deletes the difference. The
 * outbox carries retries and dead-lettering, so no request ever waits on
 * OpenFGA and a transient outage is replayed, not lost.
 *
 * <p><strong>Port addition.</strong> The reference converges only through the
 * worker, which leaves a window (its dispatch interval plus the 30 s decision
 * cache) in which the member who just gained a role is still denied — with
 * {@code SYNAPSE_AUTHZ_BACKEND=openfga} that window is long enough for the
 * acceptance suite to fail. {@link #queue} therefore ALSO converges once,
 * best effort, right after the transaction commits. The outbox event is still
 * written and still consumed, so durability, retries and dead-lettering are
 * unchanged, and the second pass is a no-op because the diff is then empty.
 */
@Component
public class TupleSync {

    private static final Logger log = LoggerFactory.getLogger(TupleSync.class);

    private final SynapseProperties props;
    private final OutboxWriter outbox;
    private final MembershipRepository memberships;
    private final FgaClient client;
    private final RlsGucs rls;
    private final Caches caches;
    private final TransactionTemplate readTx;

    /** Queued converges for the transaction in progress, de-duplicated per member. */
    private static final String PENDING_KEY = "synapse_pending_tuple_sync";
    /** Runs before {@code DeferredBumps} so the decision cache is dropped after the tuples land. */
    public static final int SYNC_ORDER = 100;

    public TupleSync(SynapseProperties props, OutboxWriter outbox, MembershipRepository memberships, FgaClient client,
                     RlsGucs rls, Caches caches, PlatformTransactionManager transactions) {
        this.props = props;
        this.outbox = outbox;
        this.memberships = memberships;
        this.client = client;
        this.rls = rls;
        this.caches = caches;
        this.readTx = new TransactionTemplate(transactions);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
    }

    /**
     * What OpenFGA must hold for one active member.
     *
     * <p>System roles map to role relations (the model derives their
     * permissions); permissions that come from custom roles only are written as
     * direct {@code can_*} tuples, so the model needs no per-tenant relations.
     */
    public static Set<FgaTuple> desiredTuples(UUID userId, UUID organizationId, List<String> roleKeys, List<String> permissionKeys) {
        String org = FgaTuple.organizationObject(organizationId);
        String user = FgaTuple.userObject(userId);
        Set<FgaTuple> tuples = new LinkedHashSet<>();
        List<String> systemRoles = roleKeys.stream().filter(FgaModel.ROLE_ORDER::contains).toList();
        systemRoles.forEach(role -> tuples.add(new FgaTuple(user, role, org)));
        Set<String> covered = new TreeSet<>();
        systemRoles.forEach(role -> covered.addAll(PermissionCatalog.SYSTEM_ROLES.get(role).permissions()));
        for (String permission : permissionKeys) {
            if (!covered.contains(permission)) {
                tuples.add(new FgaTuple(user, FgaModel.relationFor(permission), org));
            }
        }
        return tuples;
    }

    /** Record that this member's tuples must be recomputed (no-op without the OpenFGA backend). */
    public void queue(UUID organizationId, UUID userId) {
        if (userId == null || !props.openfgaBackend()) {
            return;
        }
        outbox.append(Events.AUTHZ_TUPLES_CHANGED, "membership", Ids.uuidV7(), organizationId,
            Map.of("organization_id", organizationId.toString(), "user_id", userId.toString()));
        convergeAfterCommit(organizationId, userId);
    }

    @SuppressWarnings("unchecked")
    private void convergeAfterCommit(UUID organizationId, UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            applyQuietly(organizationId, userId);
            return;
        }
        Map<UUID, UUID> pending = (Map<UUID, UUID>) TransactionSynchronizationManager.getResource(PENDING_KEY);
        if (pending == null) {
            pending = new LinkedHashMap<>();
            TransactionSynchronizationManager.bindResource(PENDING_KEY, pending);
            TransactionSynchronizationManager.registerSynchronization(new Converge(this));
        }
        pending.put(userId, organizationId); // several role writes in one request converge once
    }

    private void applyQuietly(UUID organizationId, UUID userId) {
        try {
            apply(Map.of("organization_id", organizationId.toString(), "user_id", userId.toString()));
        } catch (RuntimeException e) {
            // The outbox event is already durable: the worker will retry this.
            log.warn("fga_eager_sync_failed org={} user={} error={}", organizationId, userId, e.toString());
        }
    }

    /** Converges every member queued in the committed transaction, then drops their cached decisions. */
    private static final class Converge implements TransactionSynchronization {

        private final TupleSync sync;

        private Converge(TupleSync sync) {
            this.sync = sync;
        }

        @Override
        public int getOrder() {
            return SYNC_ORDER;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void afterCommit() {
            Map<UUID, UUID> pending = (Map<UUID, UUID>) TransactionSynchronizationManager.getResource(PENDING_KEY);
            if (pending != null) {
                pending.forEach((userId, organizationId) -> sync.applyQuietly(organizationId, userId));
            }
        }

        @Override
        public void afterCompletion(int status) {
            if (TransactionSynchronizationManager.hasResource(PENDING_KEY)) {
                TransactionSynchronizationManager.unbindResource(PENDING_KEY);
            }
        }
    }

    /** Worker side (and the eager pass): converge OpenFGA to the member's current RBAC state. */
    public Result apply(Map<String, Object> payload) {
        UUID organizationId = UUID.fromString(String.valueOf(payload.get("organization_id")));
        UUID userId = UUID.fromString(String.valueOf(payload.get("user_id")));
        if (!client.configured()) {
            log.warn("fga_sync_skipped_unconfigured org={} user={}", organizationId, userId);
            return new Result(0, 0);
        }
        // Its own transaction: under RLS the tenant GUC has to be bound before the read.
        Set<FgaTuple> desired = readTx.execute(status -> {
            rls.bindTenant(organizationId);
            return desiredFor(organizationId, userId);
        });
        String user = FgaTuple.userObject(userId);
        Set<FgaTuple> current = new LinkedHashSet<>(
            client.readTuples(FgaTuple.organizationObject(organizationId)).stream().filter(t -> t.user().equals(user)).toList());

        List<FgaTuple> writes = difference(desired, current);
        List<FgaTuple> deletes = difference(current, desired);
        client.write(writes, deletes);
        // The decision cache holds answers from before the tuples changed.
        caches.fga().bump(userId + ":" + FgaTuple.organizationObject(organizationId));
        log.info("fga_tuples_synced org={} user={} writes={} deletes={}", organizationId, userId, writes.size(), deletes.size());
        return new Result(writes.size(), deletes.size());
    }

    private Set<FgaTuple> desiredFor(UUID organizationId, UUID userId) {
        Optional<Membership> membership = memberships.findActive(organizationId, userId);
        if (membership.isEmpty()) {
            return Set.of(); // removed, suspended or still invited ⇒ no tuples
        }
        MembershipView view = memberships.findView(membership.get().id()).orElse(null);
        if (view == null) {
            return Set.of();
        }
        return desiredTuples(userId, organizationId, view.roleKeys(), membership.get().permissionKeys());
    }

    private static List<FgaTuple> difference(Set<FgaTuple> left, Set<FgaTuple> right) {
        return left.stream().filter(tuple -> !right.contains(tuple))
            .sorted(Comparator.comparing(FgaTuple::relation)).toList();
    }

    /**
     * Outbox consumer entry point (internal audience). On the rbac backend the
     * events are still emitted but the consumer is a no-op, exactly as in the
     * reference's {@code handle_event}.
     */
    public void handleEvent(String eventType, Map<String, Object> payload) {
        if (Events.AUTHZ_TUPLES_CHANGED.equals(eventType) && props.openfgaBackend()) {
            apply(payload);
        }
    }

    public record Result(int writes, int deletes) {}
}
