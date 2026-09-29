package dev.synapse.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The production guardrails (reference: {@code Settings._production_guardrails}
 * and {@code _validate_cidrs}). Dev and e2e raise the auth limits to register
 * many users from one IP; a baked {@code .env} must not carry that into production.
 */
class SynapsePropertiesGuardrailTest {

    private static SynapseProperties props(String env, String secret, int perIp, int perIdentity, List<String> proxies) {
        return new SynapseProperties("0.1.0", env, "manual", "local", "app", secret, 15, 30, 10,
            "http://localhost:3000", List.of(), null, "", "", "classpath:config/plans.yaml", true, "free", true,
            "PHP", "", "", "", "", "", "", "", "", "", Map.of(), "",
            "smtp", "", 1025, "synapse@localhost", "", "", "none",
            "", "us-east-1", "", "", "", ".storage", true, 365, 3600,
            "", perIp, perIdentity, 60, proxies,
            "rbac", "", "", "", "", "closed",
            "", "", "", "", false, "");
    }

    @Test
    void developmentAcceptsTheRaisedLimitsAndTheDevSecret() {
        assertThatCode(() -> props("development", "dev-only-secret", 1000, 100, List.of())).doesNotThrowAnyException();
    }

    @Test
    void productionRefusesLimitsAboveTheCeilings() {
        assertThatThrownBy(() -> props("production", "a-real-secret-key-32-bytes-minimum!", 1000, 5, List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SYNAPSE_AUTH_RATE_LIMIT_PER_IP=1000")
            .hasMessageContaining("ceiling of 100");
        assertThatThrownBy(() -> props("production", "a-real-secret-key-32-bytes-minimum!", 20, 100, List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SYNAPSE_AUTH_RATE_LIMIT_PER_IDENTITY=100");
    }

    @Test
    void productionStillRefusesTheDevSecretAndReportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> props("production", "dev-only-secret", 1000, 100, List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PER_IP").hasMessageContaining("PER_IDENTITY")
            .hasMessageContaining("SYNAPSE_SECRET_KEY is the dev default");
    }

    @Test
    void productionAtTheCeilingsBoots() {
        assertThatCode(() -> props("production", "a-real-secret-key-32-bytes-minimum!", 100, 20, List.of()))
            .doesNotThrowAnyException();
    }

    @Test
    void garbageTrustedProxiesRefuseToBoot() {
        assertThatThrownBy(() -> props("development", "dev-only-secret", 20, 5, List.of("not-a-cidr")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not-a-cidr");
        assertThat(props("development", "dev-only-secret", 20, 5, List.of("10.0.0.0/8", "fd00::/8")).trustedProxies())
            .containsExactly("10.0.0.0/8", "fd00::/8");
    }

    @Test
    void theBackendFlagsReadTheSettings() {
        assertThat(props("development", "dev-only-secret", 20, 5, List.of()).openfgaBackend()).isFalse();
        assertThat(props("development", "dev-only-secret", 20, 5, List.of()).keycloakIdentity()).isFalse();
    }
}
