package dev.synapse.authorization.fga;

import dev.synapse.authorization.PermissionCatalog;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.db.RlsGucs;
import dev.synapse.core.ids.Ids;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import dev.synapse.tenancy.Membership;
import dev.synapse.tenancy.MembershipRepository;
import dev.synapse.tenancy.MembershipView;
import java.util.Comparator;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RBAC (the source of truth) → OpenFGA relationship tuples
 * (ADR 0009; reference: {@code authorization/sync.py}).
 *
 * <p>Request side: {@link #queue} appends an internal outbox event inside the
 * mutating transaction. Worker side: {@link #apply} recomputes the member's
 * desired tuples from the database and writes/deletes the difference. The
 * outbox carries retries and dead-lettering, so no request ever waits on
 * OpenFGA and a transient outage is replayed, not lost.
 */
@Component
public class TupleSync {

    private static final Logger log = LoggerFactory.getLogger(TupleSync.class);

    private final SynapseProperties props;
    private final OutboxWriter outbox;
    private final MembershipRepository memberships;
    private final FgaClient client;
    private final RlsGucs rls;

    public TupleSync(SynapseProperties props, OutboxWriter outbox, MembershipRepository memberships, FgaClient client, RlsGucs rls) {
        this.props = props;
        this.outbox = outbox;
        this.memberships = memberships;
        this.client = client;
        this.rls = rls;
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
    }

    /** Worker side: converge OpenFGA to the member's current RBAC state. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Result apply(Map<String, Object> payload) {
        // Under RLS the consumer runs outside any request: bind the tenant before reading.
        UUID organizationId = UUID.fromString(String.valueOf(payload.get("organization_id")));
        UUID userId = UUID.fromString(String.valueOf(payload.get("user_id")));
        rls.bindTenant(organizationId);
        if (!client.configured()) {
            log.warn("fga_sync_skipped_unconfigured org={} user={}", organizationId, userId);
            return new Result(0, 0);
        }
        Set<FgaTuple> desired = desiredFor(organizationId, userId);
        String user = FgaTuple.userObject(userId);
        Set<FgaTuple> current = new LinkedHashSet<>(
            client.readTuples(FgaTuple.organizationObject(organizationId)).stream().filter(t -> t.user().equals(user)).toList());

        List<FgaTuple> writes = difference(desired, current);
        List<FgaTuple> deletes = difference(current, desired);
        client.write(writes, deletes);
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
