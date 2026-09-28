package dev.synapse.core.web;

import dev.synapse.core.errors.AuthenticationError;
import dev.synapse.core.security.Principal;
import dev.synapse.core.security.SynapseAuthentication;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The principal Spring Security resolved for this request. */
public final class Principals {

    private Principals() {}

    public static Principal current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof SynapseAuthentication sa) {
            return sa.getPrincipal();
        }
        throw new AuthenticationError("Missing bearer token");
    }
}
