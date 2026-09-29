package dev.synapse.core.cache;

import org.springframework.stereotype.Component;

/**
 * The framework's cache namespaces and their TTLs, exactly as the reference
 * declares them: {@code perm} 30 s and {@code fga} 30 s
 * ({@code authorization/service.py}), {@code entl} 60 s
 * ({@code entitlements/service.py}), {@code fflags} 30 s
 * ({@code feature_flags/service.py}) and {@code oidc} 600 s
 * ({@code identity/router.py}).
 *
 * <p>The reference also declares {@code member} 60 s in
 * {@code tenancy/dependencies.py:29} but never reads or writes it — see the
 * README's milestone-7 notes; this port does not carry the dead namespace.
 */
@Component
public class Caches {

    public static final int PERM_TTL_SECONDS = 30;
    public static final int FGA_TTL_SECONDS = 30;
    public static final int ENTITLEMENT_TTL_SECONDS = 60;
    public static final int FLAG_TTL_SECONDS = 30;
    public static final int OIDC_STATE_TTL_SECONDS = 600;

    private final CacheBackend backend;
    private final VersionedCache permissions;
    private final VersionedCache fga;
    private final VersionedCache entitlements;
    private final VersionedCache flags;
    private final VersionedCache oidcState;

    public Caches(CacheBackend backend) {
        this.backend = backend;
        this.permissions = new VersionedCache(backend, "perm", PERM_TTL_SECONDS);
        this.fga = new VersionedCache(backend, "fga", FGA_TTL_SECONDS);
        this.entitlements = new VersionedCache(backend, "entl", ENTITLEMENT_TTL_SECONDS);
        this.flags = new VersionedCache(backend, "fflags", FLAG_TTL_SECONDS);
        this.oidcState = new VersionedCache(backend, "oidc", OIDC_STATE_TTL_SECONDS);
    }

    public CacheBackend backend() {
        return backend;
    }

    public VersionedCache permissions() {
        return permissions;
    }

    public VersionedCache fga() {
        return fga;
    }

    public VersionedCache entitlements() {
        return entitlements;
    }

    public VersionedCache flags() {
        return flags;
    }

    public VersionedCache oidcState() {
        return oidcState;
    }
}
