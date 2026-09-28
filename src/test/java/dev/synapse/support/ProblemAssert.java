package dev.synapse.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;

/** The conformance suite's {@code assert_problem}: every error is an RFC 7807 document in the framework's shape. */
public final class ProblemAssert {

    private ProblemAssert() {}

    public static JsonNode assertProblem(ApiClient.Res res, int status) {
        return assertProblem(res, status, null);
    }

    public static JsonNode assertProblem(ApiClient.Res res, int status, String title) {
        assertThat(res.status()).as("status for %s", res.body()).isEqualTo(status);
        assertThat(res.raw().getContentType()).startsWith("application/json");
        JsonNode doc = res.body();
        assertThat(doc.has("type") && doc.has("title") && doc.has("status")).as("problem members in %s", doc).isTrue();
        assertThat(doc.get("status").asInt()).isEqualTo(status);
        assertThat(doc.get("type").asText()).startsWith("https://synapse-saas.dev/problems/");
        assertThat(doc.get("request_id").asText()).isNotBlank();
        if (title != null) {
            assertThat(doc.get("title").asText()).isEqualTo(title);
        }
        return doc;
    }
}
