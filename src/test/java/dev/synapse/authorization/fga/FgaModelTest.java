package dev.synapse.authorization.fga;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.authorization.PermissionCatalog;
import dev.synapse.authorization.PermissionDef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The generated OpenFGA model mirrors the permission catalog exactly (ADR 0009;
 * reference: {@code tests/unit/authorization/test_fga_model.py}). The DSL
 * fixture is the byte-for-byte output of the reference's {@code render_dsl()}.
 */
class FgaModelTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> organizationType() {
        List<Map<String, Object>> types = (List<Map<String, Object>>) FgaModel.build().get("type_definitions");
        return types.stream().filter(t -> "organization".equals(t.get("type"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> relations(Map<String, Object> type) {
        return (Map<String, Object>) type.get("relations");
    }

    @Test
    void everyPermissionHasARelation() {
        Map<String, Object> relations = relations(organizationType());
        for (PermissionDef permission : PermissionCatalog.PERMISSIONS) {
            assertThat(relations).containsKey(FgaModel.relationFor(permission.key()));
        }
    }

    @Test
    void everySystemRoleIsARelation() {
        assertThat(new HashSet<>(FgaModel.ROLE_ORDER)).isEqualTo(PermissionCatalog.SYSTEM_ROLES.keySet());
        assertThat(relations(organizationType())).containsKeys(FgaModel.ROLE_ORDER.toArray(String[]::new));
    }

    @Test
    @SuppressWarnings("unchecked")
    void roleToPermissionParity() {
        // For every role × permission: the model unions the role into can_* iff RBAC grants it.
        Map<String, Object> relations = relations(organizationType());
        for (String role : FgaModel.ROLE_ORDER) {
            Set<String> granted = Set.copyOf(PermissionCatalog.SYSTEM_ROLES.get(role).permissions());
            for (PermissionDef permission : PermissionCatalog.PERMISSIONS) {
                Map<String, Object> definition = (Map<String, Object>) relations.get(FgaModel.relationFor(permission.key()));
                assertThat(computedRelations(definition).contains(role))
                    .as(role + " × " + permission.key())
                    .isEqualTo(granted.contains(permission.key()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> computedRelations(Map<String, Object> definition) {
        List<Map<String, Object>> children = definition.containsKey("union")
            ? (List<Map<String, Object>>) ((Map<String, Object>) definition.get("union")).get("child")
            : List.of(definition);
        Set<String> computed = new HashSet<>();
        for (Map<String, Object> child : children) {
            Map<String, Object> userset = (Map<String, Object>) child.get("computedUserset");
            if (userset != null) {
                computed.add(String.valueOf(userset.get("relation")));
            }
        }
        return computed;
    }

    @Test
    @SuppressWarnings("unchecked")
    void directUserGrantsForCustomRoles() {
        Map<String, Object> metadata =
            (Map<String, Object>) ((Map<String, Object>) organizationType().get("metadata")).get("relations");
        for (PermissionDef permission : PermissionCatalog.PERMISSIONS) {
            Map<String, Object> entry = (Map<String, Object>) metadata.get(FgaModel.relationFor(permission.key()));
            assertThat(entry.get("directly_related_user_types")).isEqualTo(List.of(Map.of("type", "user")));
        }
    }

    @Test
    void theOperatorPermissionHasNoRole() {
        // ADR 0008: only direct grants / platform admin
        assertThat(FgaModel.rolesHolding("entitlement:manage")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectTemplate() {
        List<Map<String, Object>> types = (List<Map<String, Object>>) FgaModel.build().get("type_definitions");
        Map<String, Object> project = types.stream().filter(t -> "project".equals(t.get("type"))).findFirst().orElseThrow();
        Map<String, Object> relations = relations(project);
        assertThat(relations.keySet()).containsExactlyInAnyOrder("org", "viewer", "editor");
        List<Map<String, Object>> viewer =
            (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>) relations.get("viewer")).get("union")).get("child");
        // inherits from the org's can_project_read
        assertThat(viewer).anyMatch(child -> child.containsKey("tupleToUserset"));
    }

    @Test
    void theDslIsTheReferencesByteForByte() throws IOException {
        String expected = new String(new ClassPathResource("fga/reference-model.fga").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        assertThat(FgaModel.renderDsl()).isEqualTo(expected);
    }

    @Test
    void theDslNamesEveryRelation() {
        String dsl = FgaModel.renderDsl();
        assertThat(dsl).startsWith("model\n  schema 1.1");
        for (PermissionDef permission : PermissionCatalog.PERMISSIONS) {
            assertThat(dsl).contains("define " + FgaModel.relationFor(permission.key()) + ": [user]");
        }
        assertThat(dsl).contains("define can_org_delete: [user] or owner\n");
        assertThat(dsl).contains("define can_billing_read: [user] or owner or admin or billing\n");
        assertThat(dsl).contains("define viewer: [user] or editor or can_project_read from org");
    }

    @Test
    void theModelIsJsonSerialisable() throws Exception {
        assertThat(new ObjectMapper().writeValueAsString(FgaModel.build())).contains("\"schema_version\":\"1.1\"");
    }

    @Test
    void relationNamesReplaceTheColon() {
        List<String> relations = new ArrayList<>();
        PermissionCatalog.PERMISSIONS.forEach(p -> relations.add(FgaModel.relationFor(p.key())));
        assertThat(relations).allMatch(name -> name.startsWith("can_") && !name.contains(":"));
    }
}
