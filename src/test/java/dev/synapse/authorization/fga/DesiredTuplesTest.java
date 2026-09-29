package dev.synapse.authorization.fga;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code desired_tuples} (reference: {@code tests/unit/authorization/test_authz_backend.py:TestDesiredTuples}). */
class DesiredTuplesTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();

    private static FgaTuple tuple(String relation) {
        return new FgaTuple("user:" + USER, relation, "organization:" + ORG);
    }

    @Test
    void systemRolesBecomeRoleTuples() {
        Set<FgaTuple> tuples = TupleSync.desiredTuples(USER, ORG, List.of("admin"), List.of("org:read", "org:update"));
        assertThat(tuples).containsExactlyInAnyOrder(tuple("admin"));
    }

    @Test
    void customRolePermissionsBecomeDirectGrants() {
        // auditor is a custom role granting audit:read — the model has no relation for it
        Set<FgaTuple> tuples = TupleSync.desiredTuples(USER, ORG, List.of("member", "auditor"),
            List.of("org:read", "project:read", "audit:read"));
        assertThat(tuples).containsExactlyInAnyOrder(tuple("member"), tuple("can_audit_read"));
    }

    @Test
    void noRolesMeansNoTuples() {
        assertThat(TupleSync.desiredTuples(USER, ORG, List.of(), List.of())).isEmpty();
    }

    @Test
    void severalSystemRolesUnionTheirCoverage() {
        Set<FgaTuple> tuples = TupleSync.desiredTuples(USER, ORG, List.of("billing", "developer"),
            List.of("org:read", "billing:read", "project:manage"));
        assertThat(tuples).containsExactlyInAnyOrder(tuple("billing"), tuple("developer"));
    }
}
