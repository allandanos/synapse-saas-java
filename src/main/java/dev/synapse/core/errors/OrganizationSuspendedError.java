package dev.synapse.core.errors;

import java.util.Map;

/** Operator-suspended org: members are told why (403), never a bare 404. */
public final class OrganizationSuspendedError extends DomainError {
    public static final int STATUS = 403;
    public static final String TITLE = "organization_suspended";

    public OrganizationSuspendedError(String message) {
        this(message, Map.of());
    }

    public OrganizationSuspendedError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
