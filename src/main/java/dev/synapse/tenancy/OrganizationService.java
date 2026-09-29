package dev.synapse.tenancy;

import dev.synapse.authorization.AuthorizationService;
import dev.synapse.authorization.PermissionCatalog;
import dev.synapse.core.audit.AuditService;
import dev.synapse.core.context.TenantContext;
import dev.synapse.core.db.Json;
import dev.synapse.core.db.RlsGucs;
import dev.synapse.core.errors.ConflictError;
import dev.synapse.core.errors.InviteNotFoundError;
import dev.synapse.core.errors.NotFoundError;
import dev.synapse.core.errors.SlugUnavailableError;
import dev.synapse.core.errors.UsageLimitExceededError;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.ids.Ids;
import dev.synapse.core.ids.Secrets;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import dev.synapse.core.pagination.PageEnvelope;
import dev.synapse.core.security.Principal;
import dev.synapse.core.validation.Emails;
import dev.synapse.entitlements.EntitlementService;
import dev.synapse.identity.User;
import dev.synapse.identity.UserRepository;
import dev.synapse.identity.dto.InviteAcceptResponse;
import dev.synapse.subscriptions.Plan;
import dev.synapse.subscriptions.PlanRepository;
import dev.synapse.subscriptions.SubscriptionService;
import dev.synapse.tenancy.dto.MembershipRead;
import dev.synapse.tenancy.dto.OrganizationRead;
import dev.synapse.usage.UsageService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organization lifecycle + membership management (reference: {@code tenancy/service.py}).
 * Every mutation writes audit + outbox in the same transaction. Creating an org
 * bootstraps the owner membership with the {@code owner} system role and a
 * default-plan subscription; the {@code users} seat gauge follows every
 * membership change and is enforced on invites.
 */
@Service
public class OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);
    static final int INVITE_TOKEN_BYTES = 32;

    private final OrganizationRepository orgs;
    private final MembershipRepository members;
    private final UserRepository users;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final RlsGucs rls;
    private final Json json;
    private final SubscriptionService subscriptions;
    private final PlanRepository plans;
    private final UsageService usage;
    private final EntitlementService entitlements;
    private final SynapseProperties props;

    public OrganizationService(OrganizationRepository orgs, MembershipRepository members, UserRepository users,
                               AuthorizationService authz, AuditService audit, OutboxWriter outbox, RlsGucs rls, Json json,
                               SubscriptionService subscriptions, PlanRepository plans, UsageService usage, EntitlementService entitlements,
                               SynapseProperties props) {
        this.orgs = orgs;
        this.members = members;
        this.users = users;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.rls = rls;
        this.json = json;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.usage = usage;
        this.entitlements = entitlements;
        this.props = props;
    }

    // ── Organizations ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageEnvelope<OrganizationRead> listMyOrgs(UUID userId) {
        List<OrganizationRead> orgsOfUser = members.forUser(userId).stream().map(m -> OrganizationRead.from(m.organization())).toList();
        return PageEnvelope.of(orgsOfUser, orgsOfUser.size(), 100, 0);
    }

    /** Create org + owner membership + owner role + default subscription. */
    @Transactional
    public OrganizationRead createOrganization(String name, String slug, UUID ownerUserId) {
        String desired = slug != null ? slug : Ids.slugify(name);
        if (!desired.isEmpty() && !Ids.isValidSlug(desired)) {
            throw new SlugUnavailableError("'" + desired + "' is reserved or invalid", Map.of("slug", desired));
        }
        String finalSlug = !desired.isEmpty() && !orgs.slugExists(desired) ? desired : Ids.uniqueSlug(name);

        Organization org = orgs.insert(finalSlug, name, ownerUserId, json);
        // POST /v1/orgs is user-scoped: bind the new org so the writes below pass RLS.
        rls.bindTenant(org.id());

        Membership membership = members.insert(org.id(), ownerUserId, null, "active", Instant.now());
        syncSeatGauge(org.id());
        authz.attachRole(membership.id(), org.id(), List.of(), PermissionCatalog.SYSTEM_ROLE_OWNER);

        audit.log(Events.ORG_CREATED, org.id(), null, "organization", org.id(), Map.of("name", name, "slug", finalSlug));
        outbox.append(Events.ORG_CREATED, "organization", org.id(), org.id(),
            Map.of("name", name, "slug", finalSlug, "owner_user_id", ownerUserId.toString()));

        // Default-plan subscription so entitlements resolve immediately
        bootstrapSubscription(org);

        log.info("org_created org_id={} slug={}", org.id(), finalSlug);
        return OrganizationRead.from(org);
    }

    @Transactional(readOnly = true)
    public OrganizationRead getOrganization(UUID organizationId) {
        return OrganizationRead.from(requireOrganization(organizationId));
    }

    /** The row itself — billing and invoicing need the owner and the settings, not the projection. */
    @Transactional(readOnly = true)
    public Organization get(UUID organizationId) {
        return requireOrganization(organizationId);
    }

    @Transactional
    public OrganizationRead updateOrganization(UUID organizationId, String name, Map<String, Object> settings) {
        Organization org = requireOrganization(organizationId);
        Map<String, Object> diff = new LinkedHashMap<>();
        String newName = org.name();
        Map<String, Object> newSettings = org.settings();
        if (name != null && !name.equals(org.name())) {
            diff.put("name", Map.of("from", org.name(), "to", name));
            newName = name;
        }
        if (settings != null) {
            Map<String, Object> merged = new LinkedHashMap<>(org.settings());
            merged.putAll(settings);
            diff.put("settings", Map.of("from", org.settings(), "to", merged));
            newSettings = merged;
        }
        if (!diff.isEmpty()) {
            orgs.update(org.id(), newName, json.write(newSettings));
            audit.log(Events.ORG_UPDATED, org.id(), null, null, null, diff);
        }
        return OrganizationRead.from(requireOrganization(organizationId));
    }

    @Transactional
    public void suspendOrganization(UUID organizationId) {
        Organization org = requireOrganization(organizationId);
        orgs.updateStatus(org.id(), "suspended");
        audit.log(Events.ORG_SUSPENDED, org.id());
    }

    @Transactional
    public void unsuspendOrganization(UUID organizationId) {
        Organization org = requireOrganization(organizationId);
        orgs.updateStatus(org.id(), "active");
        audit.log(Events.ORG_UNSUSPENDED, org.id());
    }

    // ── Memberships ──────────────────────────────────────────────────────────────

    /** {@code meta.total} counts active members + pending invites (seats in use). */
    @Transactional(readOnly = true)
    public PageEnvelope<MembershipRead> listMembers(UUID organizationId, int limit, int offset) {
        List<MembershipRead> page = members.forOrganization(organizationId, limit, offset).stream().map(MembershipRead::from).toList();
        long total = members.countByStatus(organizationId, "active") + members.countByStatus(organizationId, "invited");
        return PageEnvelope.of(page, total, limit, offset);
    }

    /**
     * Invite by email. The token never enters the HTTP response: it rides the
     * internal outbox only. The {@code users} seat limit (a gauge: active members
     * + pending invites) is enforced inside the same transaction as the insert.
     */
    @Transactional
    public MembershipRead inviteMember(UUID organizationId, String rawEmail, List<String> roleKeys) {
        String email = Emails.normalize(rawEmail);
        Organization org = requireOrganization(organizationId);
        members.findByInvitedEmail(organizationId, email).ifPresent(existing -> {
            // the unique constraint would 500; say why instead
            throw new ConflictError("This email is already invited to (or a member of) the organization",
                Map.of("email", email, "membership_status", existing.status()));
        });
        Long seatLimit = entitlements.effectiveForOrg(organizationId).limitValue(UsageService.SEATS_METRIC);
        long active = members.countByStatus(organizationId, "active");
        long pending = members.countByStatus(organizationId, "invited");
        if (seatLimit != null && active + pending + 1 > seatLimit) {
            throw new UsageLimitExceededError("Seat limit reached for the current plan",
                UsageService.limitExtras(UsageService.SEATS_METRIC, seatLimit, active + pending));
        }
        Membership membership = members.insert(organizationId, null, email, "invited", null);
        List<String> keys = roleKeys.isEmpty() ? List.of("member") : roleKeys;
        List<String> permissionKeys = List.of();
        for (String key : keys) {
            permissionKeys = authz.attachRole(membership.id(), organizationId, permissionKeys, key);
        }
        String token = Secrets.urlsafeToken(INVITE_TOKEN_BYTES);
        members.setInviteTokenHash(membership.id(), Secrets.sha256Hex(token));

        audit.log(Events.MEMBER_INVITED, organizationId, null, "membership", membership.id(), Map.of("email", email, "roles", keys));
        // Public event (tenant webhooks): no credential material, ever.
        outbox.append(Events.MEMBER_INVITED, "membership", membership.id(), organizationId,
            Map.of("email", email, "org_name", org.name(), "membership_id", membership.id().toString()));
        // Internal event (email only): carries the token; never fans out. The mail names the org.
        outbox.append(Events.MEMBER_INVITE_EMAIL, "membership", membership.id(), organizationId,
            Map.of("email", email, "invite_token", token, "org_name", org.name()));
        syncSeatGauge(organizationId);
        return MembershipRead.from(members.findView(membership.id()).orElseThrow());
    }

    @Transactional
    public MembershipRead updateMembership(UUID membershipId, TenantContext tenant, List<String> roleKeys, String status) {
        Membership membership = requireTenantMembership(membershipId, tenant);
        Map<String, Object> diff = new LinkedHashMap<>();
        if (roleKeys != null) {
            authz.replaceRoles(membership.id(), membership.organizationId(), roleKeys);
            diff.put("roles", roleKeys);
        }
        if (status != null && !status.equals(membership.status())) {
            members.updateStatus(membership.id(), status);
            diff.put("status", Map.of("from", membership.status(), "to", status));
        }
        if (!diff.isEmpty()) {
            audit.log(Events.MEMBER_UPDATED, membership.organizationId(), null, "membership", membership.id(), diff);
        }
        if (diff.containsKey("status")) {
            syncSeatGauge(membership.organizationId());
        }
        return MembershipRead.from(members.findView(membership.id()).orElseThrow());
    }

    @Transactional
    public void removeMember(UUID membershipId, TenantContext tenant) {
        Membership membership = requireTenantMembership(membershipId, tenant);
        Organization org = requireOrganization(membership.organizationId());
        if (membership.userId() != null && membership.userId().equals(org.ownerUserId())) {
            throw new NotFoundError("The owner cannot be removed; transfer ownership first");
        }
        audit.log(Events.MEMBER_REMOVED, membership.organizationId(), null, "membership", membership.id(),
            Map.of("email", membership.invitedEmail() != null ? membership.invitedEmail() : String.valueOf(membership.userId())));
        members.delete(membership.id());
        syncSeatGauge(membership.organizationId());
    }

    /** Accept an invitation with its emailed token (single-use); links the caller's user to the membership. */
    @Transactional
    public InviteAcceptResponse acceptInviteByToken(String token, Principal principal) {
        String tokenHash = Secrets.sha256Hex(token);
        // No tenant is bound yet: resolve the org through the SECURITY DEFINER lookup, bind it, then read under policy.
        UUID organizationId = members.orgForInviteToken(tokenHash)
            .orElseThrow(() -> new InviteNotFoundError("Invite not found or already used"));
        rls.bindTenant(organizationId);
        Membership membership = members.findPendingByTokenHash(tokenHash)
            .orElseThrow(() -> new InviteNotFoundError("Invite not found or already used"));
        User user = users.findById(principal.id()).orElse(null);
        Membership accepted = accept(membership, user);
        return new InviteAcceptResponse(accepted.organizationId(), accepted.status());
    }

    /** Server-side acceptance by email (fixtures and products that verify email themselves). */
    @Transactional
    public Membership acceptInviteByEmail(UUID organizationId, String email) {
        rls.bindTenant(organizationId);
        Membership membership = members.findPendingInvite(organizationId, email)
            .orElseThrow(() -> new InviteNotFoundError("No pending invite for this email"));
        return accept(membership, users.findByEmail(email).orElse(null));
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private Membership accept(Membership membership, User user) {
        members.accept(membership.id(), user == null ? null : user.id(), user == null ? null : user.email(), Instant.now());
        Membership accepted = members.findById(membership.id()).orElseThrow();
        audit.log(Events.MEMBER_JOINED, accepted.organizationId(), null, "membership", accepted.id(), Map.of("email", String.valueOf(accepted.invitedEmail())));
        outbox.append(Events.MEMBER_JOINED, "membership", accepted.id(), accepted.organizationId(), Map.of("email", String.valueOf(accepted.invitedEmail())));
        syncSeatGauge(accepted.organizationId());
        return accepted;
    }

    /** Create the default-plan subscription for a brand-new org (skipped, with a warning, when the catalog is not synced). */
    private void bootstrapSubscription(Organization org) {
        Plan plan = plans.findByKey(props.defaultPlanKey(), true).orElse(null);
        if (plan == null) {
            log.warn("default_plan_missing key={}", props.defaultPlanKey());
            return;
        }
        Instant now = Instant.now();
        subscriptions.createSubscription(org.id(), plan, "active", now, now.plus(Duration.ofDays(30)), null, null, null, null);
    }

    /** {@code users} is a gauge: active members + pending invites, set after every change. */
    private void syncSeatGauge(UUID organizationId) {
        long active = members.countByStatus(organizationId, "active");
        long pending = members.countByStatus(organizationId, "invited");
        usage.setGauge(organizationId, UsageService.SEATS_METRIC, active + pending);
    }

    private Organization requireOrganization(UUID organizationId) {
        Organization org = orgs.findById(organizationId).orElse(null);
        if (org == null || org.deletedAt() != null) {
            throw new NotFoundError("Organization not found");
        }
        return org;
    }

    /** Cross-tenant rows are indistinguishable from missing ones. */
    private Membership requireTenantMembership(UUID membershipId, TenantContext tenant) {
        Membership membership = members.findById(membershipId).orElse(null);
        if (membership == null || !membership.organizationId().equals(tenant.organizationId())) {
            throw new NotFoundError("Membership not found");
        }
        return membership;
    }
}
