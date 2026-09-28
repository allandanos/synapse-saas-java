package dev.synapse.core.db;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoleIsolationCheckTest {

    @Test
    void ownerRoleWithRlsOffAndSubjectRoleWithRlsOnAreFine() {
        assertThatCode(() -> RoleIsolationCheck.assertMatches(new RoleIsolationCheck.RolePosture(true, false, true, "synapse"), false)).doesNotThrowAnyException();
        assertThatCode(() -> RoleIsolationCheck.assertMatches(new RoleIsolationCheck.RolePosture(false, false, false, "synapse_app"), true)).doesNotThrowAnyException();
    }

    @Test
    void mismatchesRefuseToStart() {
        assertThatThrownBy(() -> RoleIsolationCheck.assertMatches(new RoleIsolationCheck.RolePosture(true, false, true, "synapse"), true))
            .isInstanceOf(RoleIsolationCheck.RoleIsolationMismatchException.class).hasMessageContaining("bypasses RLS");
        assertThatThrownBy(() -> RoleIsolationCheck.assertMatches(new RoleIsolationCheck.RolePosture(false, true, false, "bypasser"), true))
            .isInstanceOf(RoleIsolationCheck.RoleIsolationMismatchException.class);
        assertThatThrownBy(() -> RoleIsolationCheck.assertMatches(new RoleIsolationCheck.RolePosture(false, false, false, "synapse_app"), false))
            .isInstanceOf(RoleIsolationCheck.RoleIsolationMismatchException.class).hasMessageContaining("zero rows");
    }
}
