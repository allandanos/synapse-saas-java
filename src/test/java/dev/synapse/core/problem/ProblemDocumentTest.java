package dev.synapse.core.problem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.synapse.core.errors.OrganizationSuspendedError;
import dev.synapse.core.errors.PermissionDeniedError;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProblemDocumentTest {

    @Test
    void domainErrorRendersTheReferenceShape() {
        var error = new OrganizationSuspendedError("Organization is suspended",
            Map.of("organization_id", "org-1", "organization_status", "suspended"));
        Map<String, Object> doc = ProblemDocument.of(error, "/v1/orgs/current", "req_1");

        assertThat(doc).containsEntry("type", "https://synapse-saas.dev/problems/organization_suspended")
            .containsEntry("title", "organization suspended")
            .containsEntry("status", 403)
            .containsEntry("detail", "Organization is suspended")
            .containsEntry("instance", "/v1/orgs/current")
            .containsEntry("request_id", "req_1")
            .containsEntry("organization_id", "org-1")
            .containsEntry("organization_status", "suspended");
    }

    @Test
    void extrasNeverShadowTheRfc7807Members() {
        var error = new PermissionDeniedError("nope", Map.of("status", 200, "type", "evil", "title", "x", "detail", "y", "request_id", "z"));
        Map<String, Object> doc = ProblemDocument.of(error, "/x", "req_2");
        assertThat(doc).containsEntry("status", 403).containsEntry("type", "https://synapse-saas.dev/problems/permission_denied")
            .containsEntry("title", "permission denied").containsEntry("detail", "nope").containsEntry("request_id", "req_2");
    }

    @Test
    void instanceAndRequestIdAreOptional() {
        Map<String, Object> doc = ProblemDocument.build(500, "internal_error", "boom", null, null, null);
        assertThat(doc).doesNotContainKeys("instance", "request_id").containsEntry("title", "internal error");
    }

    @Test
    void validationDetailNamesTheFirstThreeFields() {
        var errors = List.of(
            new ValidationErrors.FieldProblem(List.of("body", "email"), "m", "t"),
            new ValidationErrors.FieldProblem(List.of("body", "password"), "m", "t"),
            new ValidationErrors.FieldProblem(List.of("body", "display_name"), "m", "t"),
            new ValidationErrors.FieldProblem(List.of("body", "fourth"), "m", "t"));
        assertThat(ValidationErrors.detailFor(errors)).isEqualTo("Invalid request: email, password, display_name");
        assertThat(ValidationErrors.detailFor(List.of(new ValidationErrors.FieldProblem(List.of("body"), "m", "t")))).isEqualTo("Invalid request: body");
        Map<String, Object> doc = ValidationErrors.problem(errors, "/v1/auth/register", "req_3");
        assertThat(doc).containsEntry("status", 422).containsEntry("title", "validation failed");
        assertThat((List<?>) doc.get("errors")).hasSize(4);
    }
}
