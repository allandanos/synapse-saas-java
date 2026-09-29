package dev.synapse.storage;

import dev.synapse.core.errors.StorageError;
import dev.synapse.core.errors.TenantViolationError;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Object keys are always {@code {org_id}/…} — the tenant boundary applies to
 * files exactly as it does to rows, so a key from another org is a 404 the
 * same way a foreign row is.
 */
public final class StorageKeys {

    private static final Pattern KEY = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._/-]{0,500}$");

    private StorageKeys() {}

    /** Reject malformed keys, and (when an org is given) keys outside its prefix. */
    public static void validate(String key, UUID organizationId) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new StorageError("Invalid storage key");
        }
        if (organizationId != null && !key.startsWith(organizationId + "/")) {
            throw new TenantViolationError("Storage key must be prefixed with the organization id (" + organizationId + "/)");
        }
    }

    public static void validate(String key) {
        validate(key, null);
    }

    /** Build a validated org-scoped key from a caller-supplied object name. Nested paths, never traversal. */
    public static String scoped(UUID organizationId, String name) {
        String safe = name == null ? "" : name.replaceAll("^/+", "");
        if (safe.contains("/")) {
            for (String segment : safe.split("/", -1)) {
                if ("..".equals(segment)) {
                    throw new StorageError("Invalid storage key");
                }
            }
        }
        String key = organizationId + "/" + safe;
        validate(key, organizationId);
        return key;
    }
}
