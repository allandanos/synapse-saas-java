package dev.synapse.bootstrap;

import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.validation.Emails;
import dev.synapse.identity.PasswordHasher;
import dev.synapse.identity.User;
import dev.synapse.identity.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform-operator bootstrap: when {@code SYNAPSE_BOOTSTRAP_ADMIN_EMAIL} and
 * {@code SYNAPSE_BOOTSTRAP_ADMIN_PASSWORD} are set, the account is created (with
 * that password) or, if it already exists, promoted to platform admin. The
 * password of an existing account is left untouched. This is how the external
 * conformance suite ({@code SYNAPSE_CONFORMANCE_ADMIN_*}) gets an operator.
 */
@Component
@Order(2)
public class PlatformAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

    private final SynapseProperties props;
    private final UserRepository users;
    private final PasswordHasher hasher;

    public PlatformAdminBootstrap(SynapseProperties props, UserRepository users, PasswordHasher hasher) {
        this.props = props;
        this.users = users;
        this.hasher = hasher;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = props.bootstrapAdminEmail();
        String password = props.bootstrapAdminPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        ensure(Emails.normalize(email.trim()), password);
    }

    @Transactional
    public void ensure(String email, String password) {
        User existing = users.findByEmail(email).orElse(null);
        if (existing == null) {
            User created = users.insert(email, hasher.hash(password), "Platform Operator", true);
            log.info("bootstrap_admin_created user_id={}", created.id());
        } else if (!existing.platformAdmin()) {
            users.setPlatformAdmin(existing.id(), true);
            log.info("bootstrap_admin_promoted user_id={}", existing.id());
        }
    }
}
