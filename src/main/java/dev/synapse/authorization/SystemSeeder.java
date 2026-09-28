package dev.synapse.authorization;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent startup seed of the permission catalog and the five system roles
 * (reference: {@code seeds/system_seed.py}; the reference runs it from
 * {@code synapse-cli seed}, this port runs it on every boot — safe to repeat).
 * System role permissions are append-only here; removals are deliberate
 * catalog changes done by migration.
 */
@Component
@Order(1)
public class SystemSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemSeeder.class);

    private final PermissionRepository permissions;
    private final RoleRepository roles;

    public SystemSeeder(PermissionRepository permissions, RoleRepository roles) {
        this.permissions = permissions;
        this.roles = roles;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    @Transactional
    public Map<String, Integer> seed() {
        Set<String> existing = permissions.existingKeys();
        PermissionCatalog.PERMISSIONS.stream().filter(p -> !existing.contains(p.key())).forEach(permissions::insert);

        Map<String, Role> systemRoles = roles.systemRoles().stream().collect(Collectors.toMap(Role::key, Function.identity()));
        for (SystemRole definition : PermissionCatalog.SYSTEM_ROLES.values()) {
            Role role = systemRoles.get(definition.key());
            if (role == null) {
                role = roles.insert(null, definition.key(), definition.name(), definition.description(), true);
            }
            Set<String> missing = new HashSet<>(definition.permissions());
            missing.removeAll(role.permissions());
            roles.addPermissions(role.id(), missing);
        }
        log.info("system_seeded permissions={} system_roles={}", PermissionCatalog.PERMISSIONS.size(), PermissionCatalog.SYSTEM_ROLES.size());
        return Map.of("permissions", PermissionCatalog.PERMISSIONS.size(), "system_roles", PermissionCatalog.SYSTEM_ROLES.size());
    }
}
