package dev.synapse.tenancy;

import java.util.List;

/** A membership with the linked user's email/display name and its sorted role keys (what the API renders). */
public record MembershipView(Membership membership, String email, String displayName, List<String> roleKeys) {}
