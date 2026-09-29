package dev.synapse.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.synapse.authorization.fga.FgaClient;
import dev.synapse.authorization.fga.FgaError;
import dev.synapse.authorization.fga.TupleSync;
import dev.synapse.core.cache.CacheBackend;
import dev.synapse.core.cache.Caches;
import dev.synapse.core.cache.InProcessCacheBackend;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.metrics.FrameworkMetrics;
import dev.synapse.tenancy.Membership;
import dev.synapse.tenancy.MembershipRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Backend dispatch: RBAC by default, OpenFGA when configured, explicit failure
 * modes (reference: {@code tests/unit/authorization/test_authz_backend.py}).
 */
class AuthzBackendDispatchTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();

    private MembershipRepository memberships;
    private FgaClient fga;
    private SynapseProperties props;
    private CacheBackend backend;

    @BeforeEach
    void setUp() {
        memberships = mock(MembershipRepository.class);
        fga = mock(FgaClient.class);
        props = mock(SynapseProperties.class);
        backend = new InProcessCacheBackend();
        // The RBAC answer everywhere below: org:read only
        when(memberships.findActive(ORG, USER)).thenReturn(Optional.of(new Membership(
            UUID.randomUUID(), ORG, USER, null, "active", null, List.of("org:read"), null, null)));
    }

    private AuthorizationService service(String backendName, String failMode) {
        when(props.openfgaBackend()).thenReturn("openfga".equals(backendName));
        when(props.openfgaFailMode()).thenReturn(failMode);
        return new AuthorizationService(mock(RoleRepository.class), memberships, new Caches(backend),
            props, fga, mock(TupleSync.class), mock(FrameworkMetrics.class));
    }

    @Nested
    @DisplayName("dispatch")
    class Dispatch {

        @Test
        void rbacIsTheDefaultAndNeverConsultsTheStore() {
            AuthorizationService service = service("rbac", "closed");
            assertThat(service.userCan(USER, ORG, "org:read")).isTrue();
            assertThat(service.userCan(USER, ORG, "org:delete")).isFalse();
            org.mockito.Mockito.verifyNoInteractions(fga);
        }

        @Test
        void openfgaAsksTheStoreWithCatalogRelations() {
            AuthorizationService service = service("openfga", "closed");
            when(fga.check(anyString(), anyString(), anyString())).thenReturn(false);
            assertThat(service.userCan(USER, ORG, "org:delete")).isFalse();

            ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> relation = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> object = ArgumentCaptor.forClass(String.class);
            verify(fga).check(user.capture(), relation.capture(), object.capture());
            assertThat(user.getValue()).isEqualTo("user:" + USER);
            assertThat(relation.getValue()).isEqualTo("can_org_delete");
            assertThat(object.getValue()).isEqualTo("organization:" + ORG);
        }

        @Test
        void decisionsAreCachedPerUserObjectAndPermission() {
            AuthorizationService service = service("openfga", "closed");
            when(fga.check(anyString(), anyString(), anyString())).thenReturn(true);
            service.userCan(USER, ORG, "org:read");
            service.userCan(USER, ORG, "org:read");
            verify(fga, org.mockito.Mockito.times(1)).check(anyString(), anyString(), anyString());
            service.userCan(USER, ORG, "org:update"); // a different permission is a different question
            verify(fga, org.mockito.Mockito.times(2)).check(anyString(), anyString(), anyString());
        }

        @Test
        void resourceLevelChecksGoStraightToTheObject() {
            AuthorizationService service = service("openfga", "closed");
            when(fga.check(anyString(), anyString(), anyString())).thenReturn(true);
            assertThat(service.userCanOn(USER, "project:manage", "project", "p-1")).isTrue();
            verify(fga).check("user:" + USER, "can_project_manage", "project:p-1");
        }

        @Test
        void theRbacBackendRefusesNonOrgResources() {
            AuthorizationService service = service("rbac", "closed");
            assertThatThrownBy(() -> service.userCanOn(USER, "project:manage", "project", "p-1"))
                .isInstanceOf(UnsupportedOperationException.class);
            assertThat(service.userCanOn(USER, "org:read", "organization", ORG.toString())).isTrue();
        }
    }

    @Nested
    @DisplayName("failure modes")
    class FailureModes {

        @Test
        void closedDeniesOnAnOutageEvenWhenRbacWouldAllow() {
            AuthorizationService service = service("openfga", "closed");
            when(fga.check(anyString(), anyString(), anyString())).thenThrow(new FgaError("down"));
            assertThat(service.userCan(USER, ORG, "org:read")).isFalse(); // RBAC would say yes
        }

        @Test
        void theRbacFailModeFallsBackForOrganizationObjects() {
            AuthorizationService service = service("openfga", "rbac");
            when(fga.check(anyString(), anyString(), anyString())).thenThrow(new FgaError("down"));
            assertThat(service.userCan(USER, ORG, "org:read")).isTrue();
            assertThat(service.userCan(USER, ORG, "org:delete")).isFalse();
        }

        @Test
        void theRbacFailModeStillDeniesResourceObjects() {
            AuthorizationService service = service("openfga", "rbac");
            when(fga.check(anyString(), anyString(), anyString())).thenThrow(new FgaError("down"));
            assertThat(service.userCanOn(USER, "project:manage", "project", "p-1")).isFalse();
        }

        @Test
        void outagesAreNeverCached() {
            AuthorizationService service = service("openfga", "closed");
            when(fga.check(anyString(), anyString(), anyString())).thenThrow(new FgaError("down"));
            assertThat(service.userCan(USER, ORG, "org:read")).isFalse();
            org.mockito.Mockito.reset(fga);
            when(fga.check(anyString(), anyString(), anyString())).thenReturn(true);
            assertThat(service.userCan(USER, ORG, "org:read")).isTrue(); // recovered ⇒ asked again
        }
    }

    @Nested
    @DisplayName("permission cache")
    class PermissionCache {

        @Test
        void theSetIsMemoisedAndDroppedOnInvalidation() {
            AuthorizationService service = service("rbac", "closed");
            assertThat(service.permissionKeysFor(USER, ORG)).containsExactly("org:read");
            verify(memberships, org.mockito.Mockito.times(1)).findActive(ORG, USER);
            service.permissionKeysFor(USER, ORG);
            verify(memberships, org.mockito.Mockito.times(1)).findActive(ORG, USER); // served from the cache

            service.invalidateUserPermissions(USER, ORG);
            service.permissionKeysFor(USER, ORG);
            verify(memberships, org.mockito.Mockito.times(2)).findActive(ORG, USER);
        }

        @Test
        void anEmptySetIsCachedToo() {
            AuthorizationService service = service("rbac", "closed");
            UUID stranger = UUID.randomUUID();
            when(memberships.findActive(ORG, stranger)).thenReturn(Optional.empty());
            assertThat(service.permissionKeysFor(stranger, ORG)).isEmpty();
            assertThat(service.permissionKeysFor(stranger, ORG)).isEmpty();
            verify(memberships, org.mockito.Mockito.times(1)).findActive(ORG, stranger);
        }

        @Test
        void invalidationAlsoQueuesATupleResync() {
            TupleSync sync = mock(TupleSync.class);
            when(props.openfgaBackend()).thenReturn(true);
            AuthorizationService service = new AuthorizationService(mock(RoleRepository.class), memberships,
                new Caches(backend), props, fga, sync, mock(FrameworkMetrics.class));
            service.invalidateUserPermissions(USER, ORG);
            verify(sync).queue(ORG, USER);
        }

        @Test
        void aNullUserIsIgnored() {
            TupleSync sync = mock(TupleSync.class);
            AuthorizationService service = new AuthorizationService(mock(RoleRepository.class), memberships,
                new Caches(backend), props, fga, sync, mock(FrameworkMetrics.class));
            service.invalidateUserPermissions(null, ORG);
            org.mockito.Mockito.verifyNoInteractions(sync);
        }
    }

    @Test
    void theFgaDecisionCacheIsDroppedByTheSameInvalidation() {
        AuthorizationService service = service("openfga", "closed");
        when(fga.check(anyString(), anyString(), anyString())).thenReturn(true);
        service.userCan(USER, ORG, "org:read");
        service.userCan(USER, ORG, "org:read");
        verify(fga, org.mockito.Mockito.times(1)).check(any(), any(), any());
        service.invalidateUserPermissions(USER, ORG);
        service.userCan(USER, ORG, "org:read");
        verify(fga, org.mockito.Mockito.times(2)).check(any(), any(), any());
    }
}
