package dev.synapse.bootstrap;

import dev.synapse.core.config.SynapseProperties;
import dev.synapse.identity.PasswordHasher;
import dev.synapse.identity.User;
import dev.synapse.identity.UserRepository;
import dev.synapse.tenancy.OrganizationService;
import dev.synapse.tenancy.dto.OrganizationRead;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Development seed — the reference's {@code synapse-cli seed --dev}
 * ({@code seeds/dev_seed.py}): a demo org plus one user per system role, so a
 * fresh stack can be clicked through (and so the console's Playwright journeys
 * have the platform operator they log in as).
 *
 * <p>NEVER in production: {@code --seed-dev} refuses when
 * {@code SYNAPSE_ENV=production}, exactly like the reference CLI's guard.
 * Idempotent: an existing {@code owner@acme.example.com} short-circuits.
 *
 * <p>Every side effect goes through {@link OrganizationService} — org creation
 * (free subscription, seat gauge, {@code org.created}), invite, accept — so a
 * seeded stack is indistinguishable from one built through the API.
 */
@Component
@Order(4)
public class DevSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);

    public static final String COMMAND_OPTION = "seed-dev";
    public static final String PASSWORD = "password123";
    public static final String OWNER_EMAIL = "owner@acme.example.com";
    public static final String ORG_NAME = "Acme Corporation";
    public static final String ORG_SLUG = "acme";

    /** One demo user per system role; the owner also carries {@code is_platform_admin}. */
    public static final List<String> ROLE_KEYS = List.of("owner", "admin", "billing", "developer", "member");

    private final SynapseProperties props;
    private final UserRepository users;
    private final PasswordHasher hasher;
    private final OrganizationService organizations;
    private final ConfigurableApplicationContext context;

    public DevSeeder(SynapseProperties props, UserRepository users, PasswordHasher hasher,
                     OrganizationService organizations, ConfigurableApplicationContext context) {
        this.props = props;
        this.users = users;
        this.hasher = hasher;
        this.organizations = organizations;
        this.context = context;
    }

    /** {@code owner@acme.example.com}, {@code admin@acme.example.com}, … */
    public static String emailFor(String roleKey) {
        return roleKey + "@acme.example.com";
    }

    /** {@code Acme Owner}, {@code Acme Admin}, … (the reference's {@code f"Acme {role.capitalize()}"}). */
    public static String displayNameFor(String roleKey) {
        return "Acme " + roleKey.substring(0, 1).toUpperCase(Locale.ROOT) + roleKey.substring(1);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(COMMAND_OPTION)) {
            return;
        }
        if (props.isProduction()) {
            System.err.println("refusing to seed development data in production (SYNAPSE_ENV=production)");
            System.exit(SpringApplication.exit(context, () -> 2));
            return;
        }
        String summary = seed();
        System.out.println(summary);
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    /** Idempotent. Returns a one-line summary for the CLI. */
    @Transactional
    public String seed() {
        if (users.findByEmail(OWNER_EMAIL).isPresent()) {
            log.info("dev_seed_skipped reason=already_seeded");
            return "dev seed skipped: " + OWNER_EMAIL + " already exists";
        }

        // Owner first: creating the org attaches the owner role at creation.
        User owner = users.insert(OWNER_EMAIL, hasher.hash(PASSWORD), displayNameFor("owner"), true);
        OrganizationRead org = organizations.createOrganization(ORG_NAME, ORG_SLUG, owner.id());

        for (String roleKey : ROLE_KEYS) {
            if (roleKey.equals("owner")) {
                continue;
            }
            String email = emailFor(roleKey);
            users.insert(email, hasher.hash(PASSWORD), displayNameFor(roleKey), false);
            organizations.inviteMember(org.id(), email, List.of(roleKey), null);
            // Auto-accept so the demo user can log in and see the org straight away.
            organizations.acceptInviteByEmail(org.id(), email);
        }

        log.info("dev_seeded org={} roles={}", org.slug(), ROLE_KEYS);
        return "dev seed: org " + org.slug() + " + " + ROLE_KEYS.size() + " users (password " + PASSWORD + ")";
    }
}
