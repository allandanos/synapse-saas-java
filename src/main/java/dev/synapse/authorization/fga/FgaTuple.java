package dev.synapse.authorization.fga;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One relationship tuple.
 *
 * @param user     {@code user:<uuid>} (or {@code organization:<uuid>} for a parent link)
 * @param relation {@code owner}, {@code can_billing_read}, {@code editor}, …
 * @param object   {@code organization:<uuid>} / {@code project:<uuid>}
 */
public record FgaTuple(String user, String relation, String object) {

    public Map<String, String> asKey() {
        Map<String, String> key = new LinkedHashMap<>();
        key.put("user", user);
        key.put("relation", relation);
        key.put("object", object);
        return key;
    }

    public static String userObject(Object userId) {
        return "user:" + userId;
    }

    public static String organizationObject(Object organizationId) {
        return "organization:" + organizationId;
    }
}
