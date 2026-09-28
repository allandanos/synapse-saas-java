package dev.synapse.core.problem;

import dev.synapse.core.errors.DomainError;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RFC 7807 problem documents in the reference's exact shape.
 *
 * <p>Extras go in first; the RFC members ({@code type}, {@code title},
 * {@code status}, {@code detail}, {@code instance}) and {@code request_id}
 * are written last so an extension named {@code status} can never rewrite the
 * HTTP status in the body. Rendered as {@code application/json}.
 */
public final class ProblemDocument {

    private ProblemDocument() {}

    public static String typeFor(String title) {
        return DomainError.BASE_PROBLEM_URI + "/" + title;
    }

    /** {@code organization_suspended} → {@code organization suspended}. */
    public static String humanTitle(String title) {
        return title.replace('_', ' ');
    }

    public static Map<String, Object> of(DomainError error, String instance, String requestId) {
        return build(error.status(), error.title(), error.detail(), instance, requestId, error.extras());
    }

    public static Map<String, Object> build(
            int status, String title, String detail, String instance, String requestId, Map<String, Object> extras) {
        Map<String, Object> doc = new LinkedHashMap<>();
        if (extras != null) {
            doc.putAll(extras);
        }
        doc.put("type", typeFor(title));
        doc.put("title", humanTitle(title));
        doc.put("status", status);
        doc.put("detail", detail);
        if (instance != null) {
            doc.put("instance", instance);
        }
        if (requestId != null) {
            doc.put("request_id", requestId);
        }
        return doc;
    }
}
