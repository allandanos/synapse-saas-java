package dev.synapse.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The catalog is a transliteration of the reference's permissions.py; these pin the exact sets. */
class PermissionCatalogTest {

    @Test
    void catalogHasTwentyOnePermissionsShapedResourceColonAction() {
        assertThat(PermissionCatalog.PERMISSIONS).hasSize(21);
        assertThat(PermissionCatalog.PERMISSION_KEYS).hasSize(21);
        assertThat(PermissionCatalog.PERMISSIONS).allSatisfy(p -> {
            assertThat(p.key()).isEqualTo(p.resource() + ":" + p.action());
            assertThat(p.description()).isNotBlank();
        });
        assertThat(PermissionCatalog.PERMISSION_KEYS).contains("org:read", "member:invite", "billing:manage", "usage:read", "entitlement:manage");
    }

    @Test
    void systemRolesAreOrderedOwnerAdminBillingDeveloperMember() {
        assertThat(PermissionCatalog.SYSTEM_ROLES.keySet()).containsExactly("owner", "admin", "billing", "developer", "member");
    }

    @Test
    void ownerHoldsEverythingExceptEntitlementManage() {
        List<String> owner = PermissionCatalog.SYSTEM_ROLES.get("owner").permissions();
        assertThat(owner).hasSize(20).doesNotContain("entitlement:manage").isSorted();
        assertThat(Set.copyOf(owner)).containsAll(Set.of("org:delete", "role:manage", "apikey:manage", "agents:manage"));
    }

    @Test
    void adminIsOwnerWithoutOrgDelete() {
        List<String> admin = PermissionCatalog.SYSTEM_ROLES.get("admin").permissions();
        assertThat(admin).hasSize(19).doesNotContain("org:delete", "entitlement:manage").isSorted();
    }

    @Test
    void billingDeveloperAndMemberSetsMatchTheReference() {
        assertThat(PermissionCatalog.SYSTEM_ROLES.get("billing").permissions())
            .containsExactly("billing:manage", "billing:read", "org:read", "usage:read");
        assertThat(PermissionCatalog.SYSTEM_ROLES.get("developer").permissions())
            .containsExactly("agents:read", "apikey:manage", "member:read", "org:read", "project:manage", "project:read", "usage:read", "webhook:manage");
        assertThat(PermissionCatalog.SYSTEM_ROLES.get("member").permissions()).containsExactly("org:read", "project:read");
    }

    @Test
    void noTenantSystemRoleCarriesTheOperatorPermission() {
        assertThat(PermissionCatalog.PERMISSION_KEYS).contains("entitlement:manage");
        PermissionCatalog.SYSTEM_ROLES.values().forEach(role -> assertThat(role.permissions()).as(role.key()).doesNotContain("entitlement:manage"));
    }

    @Test
    void pyListRendersLikePythonRepr() {
        assertThat(AuthorizationService.pyList(List.of("nope:nope", "x:y"))).isEqualTo("['nope:nope', 'x:y']");
        assertThat(AuthorizationService.pyList(List.of())).isEqualTo("[]");
    }
}
