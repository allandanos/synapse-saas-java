package dev.synapse.identity;

import dev.synapse.core.context.RequestContext;
import dev.synapse.core.context.RequestContextHolder;
import dev.synapse.core.context.UserContext;
import dev.synapse.core.db.RlsGucs;
import dev.synapse.core.errors.AuthenticationError;
import dev.synapse.core.security.BearerAuthenticator;
import dev.synapse.core.security.Principal;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** JWT bearer → the {@code users} row it names; binds the user context and the RLS user GUC. */
@Component
public class UserTokenAuthenticator implements BearerAuthenticator {

    private final JwtCodec jwt;
    private final UserRepository users;
    private final RlsGucs rls;
    private final TransactionTemplate tx;

    public UserTokenAuthenticator(JwtCodec jwt, UserRepository users, RlsGucs rls, TransactionTemplate tx) {
        this.jwt = jwt;
        this.users = users;
        this.rls = rls;
        this.tx = tx;
    }

    @Override
    public boolean supports(String token) {
        return true; // everything that is not an sk_ key
    }

    @Override
    public Principal authenticate(String token) {
        JwtCodec.AccessClaims claims = jwt.decode(token);
        User user = tx.execute(status -> users.findById(claims.userId()).orElse(null));
        if (user == null || !user.active()) {
            throw new AuthenticationError("User not found or inactive");
        }
        // RLS: a user's own memberships are readable before a tenant is resolved (/auth/me, org listing, invites).
        rls.bindUser(user.id());
        RequestContext ctx = RequestContextHolder.get();
        if (ctx != null) {
            ctx.setUser(UserContext.ofUser(user.id(), user.email(), user.platformAdmin(), Set.of()));
        }
        return Principal.ofUser(user.id(), user.email(), user.displayName(), user.platformAdmin(), user.active());
    }
}
