package dev.synapse.tenancy;

import dev.synapse.core.context.RequestContext;
import dev.synapse.core.context.RequestContextHolder;
import dev.synapse.core.context.TenantContext;
import dev.synapse.core.db.RlsGucs;
import dev.synapse.core.errors.DomainError;
import dev.synapse.core.errors.NotFoundError;
import dev.synapse.core.errors.OrganizationSuspendedError;
import dev.synapse.core.ids.Ids;
import dev.synapse.core.security.Principal;
import dev.synapse.core.web.TenantAccess;
import dev.synapse.identity.JwtCodec;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant resolution (reference: {@code tenancy/dependencies.py}).
 *
 * <p>Order: {@code X-Org-Id} / {@code X-Org-Slug} header → subdomain → JWT
 * {@code org} claim, then a membership check. Failure is 404 — never 403 — so
 * the API does not leak which organizations exist. Suspension (403) is checked
 * after membership so a non-member learns nothing; platform admins keep read
 * access. When RLS is on, the tenant GUC is bound before the membership query.
 */
@Component
public class TenantResolver implements TenantAccess {

    private static final Set<String> NON_TENANT_SUBDOMAINS = Set.of("www", "api", "app");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /** {@code Host} without the port; a bracketed IPv6 literal keeps its brackets for {@link #isIpLiteral}. */
    static String hostName(String hostHeader) {
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end < 0 ? h : h.substring(0, end + 1);
        }
        return h.split(":")[0];
    }

    static boolean isIpLiteral(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return IPV4.matcher(bare).matches() || bare.contains(":");
    }

    private final OrganizationRepository orgs;
    private final MembershipRepository members;
    private final JwtCodec jwt;
    private final RlsGucs rls;
    private final TransactionTemplate tx;

    public TenantResolver(OrganizationRepository orgs, MembershipRepository members, JwtCodec jwt, RlsGucs rls, TransactionTemplate tx) {
        this.orgs = orgs;
        this.members = members;
        this.jwt = jwt;
        this.rls = rls;
        this.tx = tx;
    }

    @Override
    public TenantContext resolve(HttpServletRequest request, Principal principal) {
        RequestContext ctx = RequestContextHolder.require();
        TenantContext existing = ctx.tenant();
        if (existing != null && !existing.platform()) {
            return existing; // API-key auth pinned it; JWT routes resolve once per request
        }
        Object reference = resolveOrgReference(request);
        if (reference == null) {
            throw new NotFoundError("No organization context for this request");
        }
        TenantContext tenant = tx.execute(status -> {
            Organization org = reference instanceof UUID id ? orgs.findById(id).orElse(null) : orgs.findBySlug((String) reference).orElse(null);
            if (org == null || org.deletedAt() != null) {
                throw new NotFoundError("Organization not found");
            }
            // RLS: bind the tenant BEFORE the membership query — under policies it would otherwise be empty.
            rls.bindTenant(org.id());
            boolean member = members.findActive(org.id(), principal.id()).isPresent();
            if (!member && !principal.platformAdmin()) {
                throw new NotFoundError("Organization not found"); // identical response: no existence leak
            }
            if (!org.isActive() && !principal.platformAdmin()) {
                throw new OrganizationSuspendedError("Organization is suspended",
                    Map.of("organization_id", org.id().toString(), "organization_status", org.status()));
            }
            return TenantContext.of(org.id(), org.slug());
        });
        ctx.setTenant(tenant);
        return tenant;
    }

    @Override
    public TenantContext requirePlatformAdmin(Principal principal) {
        if (!principal.platformAdmin()) {
            throw new NotFoundError("Not found");
        }
        rls.bindPlatform();
        return TenantContext.platformScope();
    }

    /** {@link UUID} for an id reference, {@link String} for a slug, {@code null} when nothing names an org. */
    Object resolveOrgReference(HttpServletRequest request) {
        String orgId = request.getHeader("X-Org-Id");
        if (orgId != null && !orgId.isEmpty()) {
            try {
                return Ids.parseLenient(orgId);
            } catch (IllegalArgumentException e) {
                throw new NotFoundError("Invalid X-Org-Id header");
            }
        }
        String slug = request.getHeader("X-Org-Slug");
        if (slug != null && !slug.isEmpty()) {
            return slug;
        }
        // Subdomain: acme.localhost / acme.app.example.com (skip www, api, app). An IP literal
        // (127.0.0.1, [::1]) is not a tenant slug: fall through to the JWT claim instead of 404 for "127".
        String host = request.getHeader("Host");
        if (host != null) {
            String name = hostName(host);
            if (name.contains(".") && !isIpLiteral(name) && !NON_TENANT_SUBDOMAINS.contains(name.split("\\.")[0])) {
                return name.split("\\.")[0];
            }
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ") && !auth.startsWith("Bearer sk_")) {
            try {
                return jwt.decode(auth.substring("Bearer ".length())).organizationId();
            } catch (DomainError ignored) {
                return null; // resolution falls through
            }
        }
        return null;
    }
}
