package dev.synapse.tenancy;

import dev.synapse.core.db.Json;
import dev.synapse.core.db.Rows;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MembershipRepository {

    private static final RowMapper<Membership> MAPPER = (rs, i) -> new Membership(
        Rows.uuid(rs, "id"), Rows.uuid(rs, "organization_id"), Rows.uuid(rs, "user_id"), rs.getString("invited_email"),
        rs.getString("status"), Rows.instant(rs, "joined_at"), Rows.strings(rs, "permission_keys"),
        Rows.local(rs, "created_at"), rs.getString("invite_token_hash"));

    private static final RowMapper<MembershipView> VIEW_MAPPER = (rs, i) -> new MembershipView(
        MAPPER.mapRow(rs, i), rs.getString("user_email"), rs.getString("user_display_name"), Rows.strings(rs, "role_keys"));

    /** Membership + linked user + aggregated role keys. */
    private static final String VIEW_SELECT = """
        SELECT m.id, m.organization_id, m.user_id, m.invited_email, m.status, m.joined_at, m.permission_keys,
               m.created_at, m.invite_token_hash,
               u.email AS user_email, u.display_name AS user_display_name,
               COALESCE(array_agg(r.key ORDER BY r.key) FILTER (WHERE r.key IS NOT NULL), '{}') AS role_keys
        FROM memberships m
        LEFT JOIN users u ON u.id = m.user_id
        LEFT JOIN membership_roles mr ON mr.membership_id = m.id
        LEFT JOIN roles r ON r.id = mr.role_id
        """;
    private static final String VIEW_GROUP = " GROUP BY m.id, u.email, u.display_name ";

    private final JdbcClient jdbc;
    private final Json json;

    public MembershipRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<MembershipView> forOrganization(UUID organizationId, int limit, int offset) {
        return jdbc.sql(VIEW_SELECT + " WHERE m.organization_id = :org " + VIEW_GROUP + " ORDER BY m.created_at, m.id LIMIT :limit OFFSET :offset")
            .param("org", organizationId).param("limit", limit).param("offset", offset).query(VIEW_MAPPER).list();
    }

    public Optional<MembershipView> findView(UUID id) {
        return jdbc.sql(VIEW_SELECT + " WHERE m.id = :id " + VIEW_GROUP).param("id", id).query(VIEW_MAPPER).optional();
    }

    /** The user's ACTIVE memberships with their organizations (the {@code /auth/me} and {@code /orgs} shape). */
    public List<MembershipOrg> forUser(UUID userId) {
        return jdbc.sql("""
                SELECT o.id, o.slug, o.name, o.status, o.owner_user_id, o.settings::text AS settings, o.created_at, o.deleted_at,
                       COALESCE(array_agg(r.key ORDER BY r.key) FILTER (WHERE r.key IS NOT NULL), '{}') AS role_keys
                FROM memberships m
                JOIN organizations o ON o.id = m.organization_id
                LEFT JOIN membership_roles mr ON mr.membership_id = m.id
                LEFT JOIN roles r ON r.id = mr.role_id
                WHERE m.user_id = :userId AND m.status = 'active'
                GROUP BY m.id, o.id
                ORDER BY m.created_at, m.id
                """)
            .param("userId", userId)
            .query((rs, i) -> new MembershipOrg(new Organization(
                Rows.uuid(rs, "id"), rs.getString("slug"), rs.getString("name"), rs.getString("status"),
                Rows.uuid(rs, "owner_user_id"), json.readMap(rs.getString("settings")), Rows.local(rs, "created_at"),
                Rows.local(rs, "deleted_at")), Rows.strings(rs, "role_keys")))
            .list();
    }

    public Optional<Membership> findActive(UUID organizationId, UUID userId) {
        return jdbc.sql("SELECT * FROM memberships WHERE organization_id = :org AND user_id = :userId AND status = 'active'")
            .param("org", organizationId).param("userId", userId).query(MAPPER).optional();
    }

    public Optional<Membership> findById(UUID id) {
        return jdbc.sql("SELECT * FROM memberships WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<Membership> findPendingInvite(UUID organizationId, String email) {
        return jdbc.sql("SELECT * FROM memberships WHERE organization_id = :org AND invited_email = CAST(:email AS citext) AND status = 'invited'")
            .param("org", organizationId).param("email", email).query(MAPPER).optional();
    }

    /** Any membership row (invited, active or suspended) carrying this invited email. */
    public Optional<Membership> findByInvitedEmail(UUID organizationId, String email) {
        return jdbc.sql("SELECT * FROM memberships WHERE organization_id = :org AND invited_email = CAST(:email AS citext)")
            .param("org", organizationId).param("email", email).query(MAPPER).optional();
    }

    public Optional<Membership> findPendingByTokenHash(String tokenHash) {
        return jdbc.sql("SELECT * FROM memberships WHERE invite_token_hash = :hash AND status = 'invited'")
            .param("hash", tokenHash).query(MAPPER).optional();
    }

    /** SECURITY DEFINER lookup: the org of a pending invite token, readable before any tenant is bound (RLS). */
    public Optional<UUID> orgForInviteToken(String tokenHash) {
        return jdbc.sql("SELECT synapse_org_for_invite_token(:hash)").param("hash", tokenHash).query(UUID.class).optional();
    }

    public long countByStatus(UUID organizationId, String status) {
        return jdbc.sql("SELECT count(*) FROM memberships WHERE organization_id = :org AND status = :status")
            .param("org", organizationId).param("status", status).query(Long.class).single();
    }

    public Membership insert(UUID organizationId, UUID userId, String invitedEmail, String status, Instant joinedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO memberships (id, organization_id, user_id, invited_email, status, joined_at, permission_keys)
                VALUES (:id, :org, :userId, CAST(:email AS citext), :status, :joinedAt, '{}')
                """)
            .param("id", id).param("org", organizationId).param("userId", userId).param("email", invitedEmail)
            .param("status", status).param("joinedAt", Rows.at(joinedAt))
            .update();
        return findById(id).orElseThrow();
    }

    public void updateStatus(UUID id, String status) {
        jdbc.sql("UPDATE memberships SET status = :status, updated_at = now() WHERE id = :id")
            .param("status", status).param("id", id).update();
    }

    public void updatePermissionKeys(UUID id, Collection<String> keys) {
        jdbc.sql("UPDATE memberships SET permission_keys = :keys, updated_at = now() WHERE id = :id")
            .param("keys", keys.stream().sorted().toArray(String[]::new)).param("id", id).update();
    }

    public void setInviteTokenHash(UUID id, String tokenHash) {
        jdbc.sql("UPDATE memberships SET invite_token_hash = :hash, updated_at = now() WHERE id = :id")
            .param("hash", tokenHash).param("id", id).update();
    }

    /** Convert an invite into an active membership (single-use: the token hash is cleared). */
    public void accept(UUID id, UUID userId, String email, Instant joinedAt) {
        jdbc.sql("""
                UPDATE memberships SET user_id = COALESCE(:userId, user_id), invited_email = COALESCE(CAST(:email AS citext), invited_email),
                       status = 'active', joined_at = :joinedAt, invite_token_hash = NULL, updated_at = now()
                WHERE id = :id
                """)
            .param("userId", userId).param("email", email).param("joinedAt", Rows.at(joinedAt)).param("id", id).update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM memberships WHERE id = :id").param("id", id).update();
    }

    // ── membership_roles ─────────────────────────────────────────────────────────

    public void addRole(UUID membershipId, UUID roleId) {
        jdbc.sql("INSERT INTO membership_roles (membership_id, role_id) VALUES (:m, :r) ON CONFLICT DO NOTHING")
            .param("m", membershipId).param("r", roleId).update();
    }

    public void clearRoles(UUID membershipId) {
        jdbc.sql("DELETE FROM membership_roles WHERE membership_id = :m").param("m", membershipId).update();
    }

    /** Union of the permission keys of every role the membership holds (recomputes the denormalised column). */
    public List<String> permissionKeysFromRoles(UUID membershipId) {
        return jdbc.sql("""
                SELECT DISTINCT p.key FROM membership_roles mr
                JOIN role_permissions rp ON rp.role_id = mr.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE mr.membership_id = :m ORDER BY p.key
                """)
            .param("m", membershipId).query(String.class).list();
    }
}
