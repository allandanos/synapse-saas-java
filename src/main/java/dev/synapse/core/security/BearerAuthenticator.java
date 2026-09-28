package dev.synapse.core.security;

import dev.synapse.core.errors.DomainError;

/** One credential type behind {@code Authorization: Bearer …}: JWT access tokens or {@code sk_} API keys. */
public interface BearerAuthenticator {

    boolean supports(String token);

    /** Resolve the token to a principal, binding request/RLS context as a side effect; throws a {@link DomainError} (401) otherwise. */
    Principal authenticate(String token);
}
