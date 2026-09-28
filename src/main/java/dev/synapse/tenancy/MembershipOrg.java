package dev.synapse.tenancy;

import java.util.List;

/** A user's active membership seen from their side: the org plus the role keys they hold there. */
public record MembershipOrg(Organization organization, List<String> roleKeys) {}
