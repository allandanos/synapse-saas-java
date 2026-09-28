package dev.synapse.apikeys;

import dev.synapse.authorization.AuthorizationService;
import dev.synapse.authorization.PermissionCatalog;
import dev.synapse.core.audit.AuditService;
import dev.synapse.core.errors.ApiKeyNotFoundError;
import dev.synapse.core.errors.PermissionDeniedError;
import dev.synapse.core.ids.Secrets;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API key domain service (reference: {@code api_keys/service.py}).
 *
 * <p>Plaintext {@code sk_<43 urlsafe chars>} is generated once, hashed
 * (SHA-256) and only the hash is stored; the plaintext returns exactly once at
 * creation. A key can never exceed its creator: requested scopes must be a
 * subset of the creator's current permissions, and an empty request snapshots
 * them rather than meaning "everything".
 */
@Service
public class ApiKeyService {

    public static final String KEY_PREFIX = "sk_";
    static final int KEY_RANDOM_BYTES = 32;
    static final int PREFIX_DISPLAY_LENGTH = 8;

    public record CreatedKey(ApiKey key, String plaintext) {}

    private final ApiKeyRepository keys;
    private final AuditService audit;
    private final OutboxWriter outbox;

    public ApiKeyService(ApiKeyRepository keys, AuditService audit, OutboxWriter outbox) {
        this.keys = keys;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Transactional
    public CreatedKey createKey(UUID organizationId, String name, List<String> requestedScopes, Integer expiresInDays,
                                UUID createdByUserId, Set<String> creatorKeys) {
        List<String> unknown = new ArrayList<>(new TreeSet<>(requestedScopes));
        unknown.removeAll(PermissionCatalog.PERMISSION_KEYS);
        if (!unknown.isEmpty()) {
            throw new PermissionDeniedError("Unknown permission scopes: " + AuthorizationService.pyList(unknown), Map.of("unknown", unknown));
        }
        List<String> scopes = requestedScopes;
        if (creatorKeys != null) {
            if (scopes.isEmpty()) {
                scopes = List.copyOf(new TreeSet<>(creatorKeys));
            }
            List<String> exceeding = new ArrayList<>(new TreeSet<>(scopes));
            exceeding.removeAll(creatorKeys);
            if (!exceeding.isEmpty()) {
                throw new PermissionDeniedError("Requested scopes exceed the creator's permissions: " + AuthorizationService.pyList(exceeding),
                    Map.of("exceeds_creator", exceeding));
            }
        }
        String plaintext = KEY_PREFIX + Secrets.urlsafeToken(KEY_RANDOM_BYTES);
        Instant expiresAt = expiresInDays == null ? null : Instant.now().plus(Duration.ofDays(expiresInDays));
        ApiKey key = keys.insert(organizationId, name, plaintext.substring(0, PREFIX_DISPLAY_LENGTH), Secrets.sha256Hex(plaintext),
            scopes, expiresAt, createdByUserId);
        audit.log(Events.API_KEY_CREATED, organizationId, null, "api_key", key.id(), Map.of("name", name, "scopes", scopes));
        outbox.append(Events.API_KEY_CREATED, "api_key", key.id(), organizationId, Map.of("name", name, "scopes", scopes));
        return new CreatedKey(key, plaintext);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> listKeys(UUID organizationId) {
        return keys.listForOrganization(organizationId);
    }

    @Transactional
    public void revokeKey(UUID keyId, UUID organizationId) {
        ApiKey key = keys.findById(keyId).orElse(null);
        if (key == null || !key.organizationId().equals(organizationId)) {
            throw new ApiKeyNotFoundError("API key not found"); // 404 cross-tenant, identical to nonexistent
        }
        keys.revoke(key.id(), Instant.now());
        audit.log(Events.API_KEY_REVOKED, organizationId, null, "api_key", key.id(), null);
        outbox.append(Events.API_KEY_REVOKED, "api_key", key.id(), organizationId, Map.of("name", key.name()));
    }

    /** Plaintext → active row, or empty. Callers treat empty as opaque 401 material. */
    @Transactional
    public Optional<ApiKey> verify(String plaintext) {
        if (!plaintext.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Optional<ApiKey> key = keys.findByHash(Secrets.sha256Hex(plaintext)).filter(k -> k.isActive(now));
        key.ifPresent(k -> keys.touchLastUsed(k.id(), now));
        return key;
    }
}
