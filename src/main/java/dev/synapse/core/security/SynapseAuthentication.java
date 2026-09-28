package dev.synapse.core.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Spring Security token wrapping a resolved {@link Principal}; permissions are checked by the guards, not by authorities. */
public final class SynapseAuthentication extends AbstractAuthenticationToken {

    private final transient Principal principal;

    public SynapseAuthentication(Principal principal) {
        super(List.of(new SimpleGrantedAuthority(principal.isApiKey() ? "ROLE_API_KEY" : "ROLE_USER")));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Principal getPrincipal() {
        return principal;
    }
}
