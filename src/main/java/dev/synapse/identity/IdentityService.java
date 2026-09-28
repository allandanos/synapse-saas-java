package dev.synapse.identity;

import dev.synapse.core.audit.AuditService;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.errors.AuthenticationError;
import dev.synapse.core.errors.EmailAlreadyRegisteredError;
import dev.synapse.core.errors.InvalidCredentialsError;
import dev.synapse.core.errors.NotFoundError;
import dev.synapse.core.errors.TokenReuseError;
import dev.synapse.core.errors.UserNotFoundError;
import dev.synapse.core.ids.Secrets;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import dev.synapse.core.validation.Emails;
import dev.synapse.identity.dto.AuthResponse;
import dev.synapse.identity.dto.OrgSummary;
import dev.synapse.identity.dto.SwitchOrgResponse;
import dev.synapse.identity.dto.TokenPair;
import dev.synapse.identity.dto.UserRead;
import dev.synapse.identity.dto.UserWithOrgs;
import dev.synapse.tenancy.MembershipRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registration, login, refresh-token rotation with reuse detection, logout,
 * org switching, password reset (reference: {@code identity/service.py}).
 *
 * <p>Rotation model: every refresh mints a new token and links old→new via
 * {@code replaced_by_token_id}. Presenting an already-rotated token outside a
 * small grace window (concurrent tabs) is a theft signal — the whole chain is
 * revoked (in its own committed transaction) and the event audited.
 */
@Service
public class IdentityService {

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);
    static final int REFRESH_TOKEN_BYTES = 32;
    static final int RESET_TOKEN_BYTES = 32;
    static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(30);

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final MembershipRepository memberships;
    private final PasswordHasher hasher;
    private final JwtCodec jwt;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final SynapseProperties props;
    private final TransactionTemplate requiresNew;

    public IdentityService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordResetTokenRepository resetTokens,
                           MembershipRepository memberships, PasswordHasher hasher, JwtCodec jwt, AuditService audit,
                           OutboxWriter outbox, SynapseProperties props, PlatformTransactionManager txManager) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.resetTokens = resetTokens;
        this.memberships = memberships;
        this.hasher = hasher;
        this.jwt = jwt;
        this.audit = audit;
        this.outbox = outbox;
        this.props = props;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ── Registration / login ─────────────────────────────────────────────────────

    @Transactional
    public AuthResponse register(String rawEmail, String password, String displayName) {
        String email = Emails.normalize(rawEmail);
        if (users.findByEmail(email).isPresent()) {
            throw new EmailAlreadyRegisteredError("An account with this email already exists");
        }
        User user = users.insert(email, hasher.hash(password), displayName, false);
        audit.log(Events.USER_REGISTERED, null, user.id(), null, null, Map.of("email", email));
        return new AuthResponse(UserRead.from(user), issueTokens(user, null, null, null));
    }

    @Transactional
    public AuthResponse login(String rawEmail, String password, String userAgent, String ip) {
        String email = Emails.normalize(rawEmail);
        User user = users.findByEmail(email).orElse(null);
        if (user != null && !"local".equals(user.identityProvider()) && user.passwordHash() == null) {
            hasher.verify(password, PasswordHasher.DUMMY_HASH);
            throw new AuthenticationError("This account signs in with single sign-on",
                Map.of("sso_url", "/v1/auth/oidc/start", "identity_provider", user.identityProvider()));
        }
        if (user == null || user.passwordHash() == null || !user.active()) {
            if (user == null) {
                hasher.verify(password, PasswordHasher.DUMMY_HASH); // timing equalisation
            }
            throw new InvalidCredentialsError("Invalid email or password");
        }
        if (!hasher.verify(password, user.passwordHash())) {
            audit.log(Events.USER_LOGIN_FAILED, null, user.id(), null, null, Map.of("email", email));
            throw new InvalidCredentialsError("Invalid email or password");
        }
        Instant now = Instant.now();
        users.touchLastLogin(user.id(), now);
        audit.log(Events.USER_LOGIN_SUCCEEDED, null, user.id(), null, null, null);
        User refreshed = users.findById(user.id()).orElse(user);
        return new AuthResponse(UserRead.from(refreshed), issueTokens(refreshed, null, userAgent, ip));
    }

    @Transactional(readOnly = true)
    public UserWithOrgs me(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new UserNotFoundError("User not found"));
        List<OrgSummary> orgs = memberships.forUser(userId).stream()
            .map(m -> new OrgSummary(m.organization().id(), m.organization().slug(), m.organization().name(), m.roleKeys()))
            .toList();
        return UserWithOrgs.from(user, orgs);
    }

    /** Mint a pair scoped to an org the user is an active member of; the reference answers 200 with the access token. */
    @Transactional
    public SwitchOrgResponse switchOrg(UUID userId, UUID organizationId) {
        if (memberships.findActive(organizationId, userId).isEmpty()) {
            throw new NotFoundError("Organization not found");
        }
        User user = users.findById(userId).orElseThrow(() -> new UserNotFoundError("User not found"));
        TokenPair pair = issueTokens(user, organizationId, null, null);
        return new SwitchOrgResponse(pair.accessToken(), pair.tokenType(), pair.expiresIn());
    }

    // ── Tokens ───────────────────────────────────────────────────────────────────

    @Transactional
    public TokenPair refresh(String refreshToken, String userAgent, String ip) {
        String tokenHash = Secrets.sha256Hex(refreshToken);
        RefreshToken row = refreshTokens.findByHash(tokenHash).orElseThrow(() -> new AuthenticationError("Invalid refresh token"));
        Instant now = Instant.now();
        if (row.expiresAt().isBefore(now)) {
            throw new AuthenticationError("Refresh token expired");
        }
        if (row.isRevoked()) {
            Duration grace = Duration.ofSeconds(props.refreshReuseGraceSeconds());
            boolean withinGrace = row.replacedByTokenId() != null && Duration.between(row.revokedAt(), now).compareTo(grace) <= 0;
            if (!withinGrace) {
                // The revocation MUST outlive the error about to be raised: commit it on its own.
                requiresNew.executeWithoutResult(status -> {
                    refreshTokens.revokeAllActiveForUser(row.userId(), now);
                    audit.log(Events.USER_TOKEN_REUSE_DETECTED, null, row.userId(), null, null, null);
                });
                throw new TokenReuseError("Refresh token reuse detected; session revoked");
            }
            throw new AuthenticationError("Refresh token already used");
        }
        User user = users.findById(row.userId()).orElse(null);
        if (user == null || !user.active()) {
            throw new AuthenticationError("Invalid refresh token");
        }
        IssuedTokens issued = issue(user, row.organizationId(), userAgent, ip);
        refreshTokens.markRotated(row.id(), issued.refreshRowId(), now);
        audit.log(Events.USER_TOKEN_REFRESHED, null, user.id(), null, null, null);
        return issued.pair();
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null) {
            return;
        }
        refreshTokens.findByHash(Secrets.sha256Hex(refreshToken)).ifPresent(row -> {
            if (!row.isRevoked()) {
                refreshTokens.revoke(row.id(), Instant.now());
                audit.log(Events.USER_LOGGED_OUT, null, row.userId(), null, null, null);
            }
        });
    }

    // ── Password reset ───────────────────────────────────────────────────────────

    /** 202 whether or not the email exists; the link rides the internal outbox and never the HTTP response. */
    @Transactional
    public void requestPasswordReset(String rawEmail) {
        String email = Emails.normalize(rawEmail);
        User user = users.findByEmail(email).orElse(null);
        if (user == null) {
            return;
        }
        String token = Secrets.urlsafeToken(RESET_TOKEN_BYTES);
        resetTokens.insert(user.id(), Secrets.sha256Hex(token), Instant.now().plus(RESET_TOKEN_TTL));
        audit.log(Events.USER_PASSWORD_RESET_REQUESTED, null, user.id(), null, null, null);
        log.info("password_reset_requested user_id={}", user.id());
        outbox.append(Events.USER_PASSWORD_RESET_LINK, "user", user.id(), null, Map.of("email", email, "token", token));
    }

    @Transactional
    public AuthResponse resetPassword(String token, String newPassword) {
        Instant now = Instant.now();
        PasswordResetTokenRepository.ResetToken row = resetTokens.findUsable(Secrets.sha256Hex(token), now)
            .orElseThrow(() -> new AuthenticationError("Invalid or expired reset token"));
        User user = users.findById(row.userId()).orElseThrow(() -> new UserNotFoundError("User not found"));
        users.updatePasswordHash(user.id(), hasher.hash(newPassword));
        resetTokens.markUsed(row.id(), now);
        refreshTokens.revokeAllActiveForUser(user.id(), now); // all sessions die on password change
        audit.log(Events.USER_PASSWORD_RESET_COMPLETED, null, user.id(), null, null, null);
        return new AuthResponse(UserRead.from(user), issueTokens(user, null, null, null));
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private record IssuedTokens(TokenPair pair, UUID refreshRowId) {}

    private TokenPair issueTokens(User user, UUID organizationId, String userAgent, String ip) {
        return issue(user, organizationId, userAgent, ip).pair();
    }

    private IssuedTokens issue(User user, UUID organizationId, String userAgent, String ip) {
        String refreshToken = Secrets.urlsafeToken(REFRESH_TOKEN_BYTES);
        UUID rowId = refreshTokens.insert(user.id(), Secrets.sha256Hex(refreshToken), organizationId,
            Instant.now().plusSeconds(props.refreshTokenTtlSeconds()), userAgent, ip);
        String access = jwt.createAccessToken(user.id(), user.email(), organizationId, user.platformAdmin());
        return new IssuedTokens(TokenPair.bearer(access, refreshToken, jwt.ttlSeconds()), rowId);
    }
}
