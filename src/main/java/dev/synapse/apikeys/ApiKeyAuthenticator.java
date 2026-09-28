package dev.synapse.apikeys;

import dev.synapse.core.context.RequestContext;
import dev.synapse.core.context.RequestContextHolder;
import dev.synapse.core.context.TenantContext;
import dev.synapse.core.context.UserContext;
import dev.synapse.core.db.RlsGucs;
import dev.synapse.core.errors.AuthenticationError;
import dev.synapse.core.security.ApiKeyPrincipal;
import dev.synapse.core.security.BearerAuthenticator;
import dev.synapse.core.security.Principal;
import dev.synapse.tenancy.Organization;
import dev.synapse.tenancy.OrganizationRepository;
import dev.synapse.usage.UsageService;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code sk_…} bearer → the key's organization. Pins the tenant (no
 * {@code X-Org-Id} needed or honoured) and binds a key principal whose
 * permissions are its scopes. Revoked/expired/unknown keys and keys of a
 * suspended org all answer the same opaque 401.
 *
 * <p>Every key-authenticated call meters one {@code api_requests} unit through
 * {@code record} (never blocks): metering must not reject an API call
 * mid-flight past its quota — {@code consume} on the metric is the domain
 * app's explicit choice. The write runs in its own transaction and a failure
 * only logs (the reference's savepoint): metering never fails the request.
 */
@Component
@Order(0)
public class ApiKeyAuthenticator implements BearerAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticator.class);

    private final ApiKeyService service;
    private final OrganizationRepository orgs;
    private final RlsGucs rls;
    private final TransactionTemplate tx;
    private final UsageService usage;

    public ApiKeyAuthenticator(ApiKeyService service, OrganizationRepository orgs, RlsGucs rls, TransactionTemplate tx, UsageService usage) {
        this.service = service;
        this.orgs = orgs;
        this.rls = rls;
        this.tx = tx;
        this.usage = usage;
    }

    @Override
    public boolean supports(String token) {
        return token.startsWith(ApiKeyService.KEY_PREFIX);
    }

    @Override
    public Principal authenticate(String token) {
        record Resolved(ApiKey key, Organization org) {}
        Resolved resolved = tx.execute(status -> {
            ApiKey key = service.verify(token).orElseThrow(() -> new AuthenticationError("Invalid API key"));
            Organization org = orgs.findById(key.organizationId()).orElse(null);
            if (org == null || org.deletedAt() != null || !org.isActive()) {
                throw new AuthenticationError("Invalid API key");
            }
            return new Resolved(key, org);
        });
        ApiKey key = resolved.key();
        ApiKeyPrincipal keyPrincipal = new ApiKeyPrincipal(key.id(), key.prefix(), key.name(), key.organizationId(),
            resolved.org().slug(), key.scopes(), key.createdByUserId());
        Principal principal = Principal.ofApiKey(keyPrincipal);
        RequestContext ctx = RequestContextHolder.get();
        if (ctx != null) {
            ctx.setTenant(TenantContext.of(key.organizationId(), resolved.org().slug()));
            ctx.setUser(new UserContext(principal.id(), principal.email(), false, Set.copyOf(key.scopes()),
                key.id(), Set.copyOf(key.scopes()), key.createdByUserId()));
        }
        // The key IS its org's credential: bind the RLS tenant for everything that follows (metering inserts usage_events).
        rls.bindTenant(key.organizationId());
        meterRequest(key.organizationId());
        return principal;
    }

    /** Count one api_requests unit for a key-authenticated call; best-effort. */
    private void meterRequest(UUID organizationId) {
        try {
            usage.record(organizationId, UsageService.API_REQUESTS_METRIC, 1, null, null, null);
        } catch (RuntimeException e) {
            log.warn("api_key_metering_failed organization_id={} error={}", organizationId, e.toString());
        }
    }

    static UUID sentinel() {
        return UUID.randomUUID();
    }
}
