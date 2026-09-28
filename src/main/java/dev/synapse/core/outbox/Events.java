package dev.synapse.core.outbox;

import java.util.Set;

/**
 * Canonical event-type vocabulary (reference: {@code core/events.py},
 * pinned by {@code contracts/events.json}). Internal events carry credentials
 * or per-recipient material and never fan out to tenant webhooks.
 */
public final class Events {

    private Events() {}

    // Identity
    public static final String USER_REGISTERED = "user.registered";
    public static final String USER_LOGIN_SUCCEEDED = "user.login_succeeded";
    public static final String USER_LOGIN_FAILED = "user.login_failed";
    public static final String USER_TOKEN_REFRESHED = "user.token_refreshed";
    public static final String USER_TOKEN_REUSE_DETECTED = "user.token_reuse_detected";
    public static final String USER_LOGGED_OUT = "user.logged_out";
    public static final String USER_PASSWORD_RESET_REQUESTED = "user.password_reset_requested";
    public static final String USER_PASSWORD_RESET_COMPLETED = "user.password_reset_completed";

    // Tenancy
    public static final String ORG_CREATED = "org.created";
    public static final String ORG_UPDATED = "org.updated";
    public static final String ORG_SUSPENDED = "org.suspended";
    public static final String ORG_UNSUSPENDED = "org.unsuspended";
    public static final String MEMBER_INVITED = "member.invited";
    public static final String MEMBER_JOINED = "member.joined";
    public static final String MEMBER_UPDATED = "member.updated";
    public static final String MEMBER_REMOVED = "member.removed";
    public static final String ROLE_CREATED = "role.created";
    public static final String ROLE_UPDATED = "role.updated";
    public static final String ROLE_DELETED = "role.deleted";
    public static final String MEMBER_ROLE_ASSIGNED = "member.role_assigned";
    public static final String MEMBER_ROLE_REVOKED = "member.role_revoked";

    // API keys
    public static final String API_KEY_CREATED = "api_key.created";
    public static final String API_KEY_REVOKED = "api_key.revoked";
    public static final String API_KEY_AUTHENTICATED = "api_key.authenticated";

    // Internal (in-process consumers only)
    public static final String MEMBER_INVITE_EMAIL = "member.invite_email";
    public static final String USER_PASSWORD_RESET_LINK = "user.password_reset_link";
    public static final String INVOICE_EMAIL = "invoice.email";
    public static final String AUTHZ_TUPLES_CHANGED = "authz.tuples_changed";

    public static final Set<String> INTERNAL_EVENTS =
        Set.of(MEMBER_INVITE_EMAIL, USER_PASSWORD_RESET_LINK, INVOICE_EMAIL, AUTHZ_TUPLES_CHANGED);

    public static final String AUDIENCE_PUBLIC = "public";
    public static final String AUDIENCE_INTERNAL = "internal";

    public static String audienceFor(String eventType) {
        return INTERNAL_EVENTS.contains(eventType) ? AUDIENCE_INTERNAL : AUDIENCE_PUBLIC;
    }
}
