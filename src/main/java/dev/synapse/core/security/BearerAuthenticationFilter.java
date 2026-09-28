package dev.synapse.core.security;

import dev.synapse.core.errors.DomainError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer → principal. {@code sk_…} is an API key, anything else a JWT. A failed
 * credential is remembered on the request (not thrown) so public routes ignore
 * it and {@link ProblemAuthenticationEntryPoint} renders it as the 401 problem
 * on protected ones — the reference decodes only where a route needs it.
 */
public class BearerAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = BearerAuthenticationFilter.class.getName() + ".error";
    private static final String PREFIX = "Bearer ";

    private final List<BearerAuthenticator> authenticators;

    public BearerAuthenticationFilter(List<BearerAuthenticator> authenticators) {
        this.authenticators = List.copyOf(authenticators);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length());
            try {
                Principal principal = authenticators.stream()
                    .filter(a -> a.supports(token))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no authenticator for bearer"))
                    .authenticate(token);
                SecurityContextHolder.getContext().setAuthentication(new SynapseAuthentication(principal));
            } catch (DomainError error) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, error);
            }
        }
        chain.doFilter(request, response);
    }
}
