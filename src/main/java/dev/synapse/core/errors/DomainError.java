package dev.synapse.core.errors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base of the domain error hierarchy (reference: {@code core/errors.py}).
 *
 * <p>Carries the HTTP status the API layer maps it to, the stable problem
 * {@code title} (the key in {@code contracts/problems.json}), and typed
 * {@code extras} merged into the problem document. Services never build HTTP
 * responses; transport code never invents error semantics.
 */
public class DomainError extends RuntimeException {

    public static final String BASE_PROBLEM_URI = "https://synapse-saas.dev/problems";

    private final int status;
    private final String title;
    private final Map<String, Object> extras;

    public DomainError(int status, String title, String message, Map<String, Object> extras) {
        super(message == null || message.isEmpty() ? title : message);
        this.status = status;
        this.title = title;
        this.extras = extras == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extras));
    }

    public int status() {
        return status;
    }

    /** Machine-readable title, e.g. {@code organization_suspended}. */
    public String title() {
        return title;
    }

    public String detail() {
        return getMessage();
    }

    public Map<String, Object> extras() {
        return extras;
    }

    public String problemType() {
        return BASE_PROBLEM_URI + "/" + title;
    }
}
