package dev.synapse.billing.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.billing.BillingProvider;
import dev.synapse.billing.ProviderHttp;
import dev.synapse.billing.Signatures;
import dev.synapse.billing.WebhookRequest;
import dev.synapse.core.errors.WebhookSignatureInvalidError;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The verification matrix every provider owes: a valid signature, a tampered
 * body, the wrong secret, a stale timestamp and a malformed header.
 * A replayed event id is the ledger's job, not the provider's — see the journeys.
 */
class ProviderWebhookSignatureTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] BODY = "{\"id\":\"evt_1\",\"type\":\"invoice.paid\"}".getBytes(StandardCharsets.UTF_8);
    private static final ProviderHttp HTTP = new ProviderHttp(JSON);

    /** Pinned against the reference's `core.security.sign_payload` for the same inputs. */
    @Test
    void stripeStyleSignatureMatchesTheReference() {
        assertThat(Signatures.signPayload(BODY, "whsec_test", 1_750_000_000L))
            .isEqualTo("4677943f49795a7b44e57a5e792aa7f776a2b1732b411cb12acf1bc5b663a5db");
    }

    /** Paddle signs with a colon, not Stripe's dot — a different digest for the same inputs. */
    @Test
    void paddleSignatureMatchesTheReference() {
        assertThat(Signatures.signPaddle(BODY, "pdl_secret", 1_750_000_000L))
            .isEqualTo("6df4a75c247f65566d0bd4d459a0d6b6bfdf16d22d65b15f9e6bbbc13fee4fd6");
        assertThat(Signatures.signPaddle(BODY, "pdl_secret", 1_750_000_000L))
            .isNotEqualTo(Signatures.signPayload(BODY, "pdl_secret", 1_750_000_000L));
    }

    @Nested
    class Stripe {

        private final StripeBillingProvider provider =
            new StripeBillingProvider(HTTP, JSON, "sk_test", "whsec_test", "http://localhost:1/v1");

        @Test
        void acceptsAFreshSignature() {
            long now = Instant.now().getEpochSecond();
            assertThat(provider.verifyWebhook(signed(now, Signatures.signPayload(BODY, "whsec_test", now), BODY)).providerEventId())
                .isEqualTo("evt_1");
        }

        @Test
        void rejectsATamperedBody() {
            long now = Instant.now().getEpochSecond();
            String signature = Signatures.signPayload(BODY, "whsec_test", now);
            byte[] tampered = "{\"id\":\"evt_1\",\"type\":\"invoice.failed\"}".getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> provider.verifyWebhook(signed(now, signature, tampered)))
                .isInstanceOf(WebhookSignatureInvalidError.class).hasMessageContaining("signature mismatch");
        }

        @Test
        void rejectsTheWrongSecret() {
            long now = Instant.now().getEpochSecond();
            assertThatThrownBy(() -> provider.verifyWebhook(signed(now, Signatures.signPayload(BODY, "whsec_other", now), BODY)))
                .isInstanceOf(WebhookSignatureInvalidError.class).hasMessageContaining("signature mismatch");
        }

        @Test
        void rejectsAStaleTimestamp() {
            long stale = Instant.now().getEpochSecond() - Signatures.WEBHOOK_TOLERANCE_SECONDS - 1;
            assertThatThrownBy(() -> provider.verifyWebhook(signed(stale, Signatures.signPayload(BODY, "whsec_test", stale), BODY)))
                .isInstanceOf(WebhookSignatureInvalidError.class).hasMessageContaining("tolerance");
        }

        @Test
        void rejectsAMalformedHeader() {
            assertThatThrownBy(() -> provider.verifyWebhook(
                new WebhookRequest(Map.of(StripeBillingProvider.SIGNATURE_HEADER, "nonsense"), BODY)))
                .isInstanceOf(WebhookSignatureInvalidError.class).hasMessageContaining("Malformed");
        }

        @Test
        void rejectsAMissingHeader() {
            assertThatThrownBy(() -> provider.verifyWebhook(new WebhookRequest(Map.of(), BODY)))
                .isInstanceOf(WebhookSignatureInvalidError.class);
        }

        private WebhookRequest signed(long timestamp, String signature, byte[] body) {
            return new WebhookRequest(Map.of(StripeBillingProvider.SIGNATURE_HEADER, "t=" + timestamp + ",v1=" + signature), body);
        }
    }

    @Nested
    class PayMongo {

        private final PayMongoBillingProvider provider =
            new PayMongoBillingProvider(HTTP, JSON, "sk_test", "whsk_test", "PHP", "http://localhost:1/v1");

        @Test
        void acceptsAFreshSignature() {
            long now = Instant.now().getEpochSecond();
            assertThat(provider.verifyWebhook(signed(now, Signatures.signPayload(BODY, "whsk_test", now))).providerEventId())
                .isEqualTo("evt_1");
        }

        @Test
        void rejectsTheWrongSecretAndAStaleTimestamp() {
            long now = Instant.now().getEpochSecond();
            assertThatThrownBy(() -> provider.verifyWebhook(signed(now, Signatures.signPayload(BODY, "other", now))))
                .isInstanceOf(WebhookSignatureInvalidError.class).hasMessageContaining("mismatch");
            long stale = now - Signatures.WEBHOOK_TOLERANCE_SECONDS - 1;
            assertThatThrownBy(() -> provider.verifyWebhook(signed(stale, Signatures.signPayload(BODY, "whsk_test", stale))))
                .isInstanceOf(WebhookSignatureInvalidError.class).hasMessageContaining("tolerance");
        }

        private WebhookRequest signed(long timestamp, String signature) {
            return new WebhookRequest(Map.of(PayMongoBillingProvider.SIGNATURE_HEADER, "t=" + timestamp + ",v1=" + signature), BODY);
        }
    }

    @Nested
    class Paddle {

        private final PaddleBillingProvider provider =
            new PaddleBillingProvider(HTTP, JSON, "pdl_key", "pdl_secret", "http://localhost:1");

        /** Paddle carries its id in `event_id`, so the body has its own shape. */
        private static final byte[] PADDLE_BODY =
            "{\"event_id\":\"evt_pdl\",\"event_type\":\"subscription.activated\"}".getBytes(StandardCharsets.UTF_8);

        @Test
        void acceptsBothSeparatorsInTheHeader() {
            long now = Instant.now().getEpochSecond();
            String signature = Signatures.signPaddle(PADDLE_BODY, "pdl_secret", now);
            assertThat(provider.verifyWebhook(header("ts=" + now + ";h1=" + signature, PADDLE_BODY)).providerEventId())
                .isEqualTo("evt_pdl");
            assertThat(provider.verifyWebhook(header("ts=" + now + ",h1=" + signature, PADDLE_BODY)).eventType())
                .isEqualTo("subscription.activated");
        }

        @Test
        void rejectsTheStripeSchemeAndAStaleTimestamp() {
            long now = Instant.now().getEpochSecond();
            assertThatThrownBy(() -> provider.verifyWebhook(header("ts=" + now + ";h1=" + Signatures.signPayload(BODY, "pdl_secret", now))))
                .isInstanceOf(WebhookSignatureInvalidError.class).hasMessageContaining("mismatch");
            long stale = now - Signatures.WEBHOOK_TOLERANCE_SECONDS - 1;
            assertThatThrownBy(() -> provider.verifyWebhook(header("ts=" + stale + ";h1=" + Signatures.signPaddle(BODY, "pdl_secret", stale))))
                .isInstanceOf(WebhookSignatureInvalidError.class).hasMessageContaining("tolerance");
        }

        @Test
        void rejectsAMissingHeader() {
            assertThatThrownBy(() -> provider.verifyWebhook(new WebhookRequest(Map.of(), BODY)))
                .isInstanceOf(WebhookSignatureInvalidError.class).hasMessageContaining("mismatch");
        }

        private WebhookRequest header(String value) {
            return header(value, BODY);
        }

        private WebhookRequest header(String value, byte[] body) {
            return new WebhookRequest(Map.of(PaddleBillingProvider.SIGNATURE_HEADER, value), body);
        }
    }

    @Nested
    class Xendit {

        private final XenditBillingProvider provider =
            new XenditBillingProvider(HTTP, JSON, "xnd_key", "callback-token", "PHP", "http://localhost:1");

        @Test
        void acceptsTheConfiguredToken() {
            assertThat(provider.verifyWebhook(new WebhookRequest(Map.of(XenditBillingProvider.TOKEN_HEADER, "callback-token"),
                "{\"id\":\"inv_1\",\"status\":\"PAID\"}".getBytes(StandardCharsets.UTF_8))).eventType()).isEqualTo("PAID");
        }

        @Test
        void rejectsAWrongOrMissingToken() {
            assertThatThrownBy(() -> provider.verifyWebhook(new WebhookRequest(Map.of(XenditBillingProvider.TOKEN_HEADER, "nope"), BODY)))
                .isInstanceOf(WebhookSignatureInvalidError.class).hasMessageContaining("X-Callback-Token");
            assertThatThrownBy(() -> provider.verifyWebhook(new WebhookRequest(Map.of(), BODY)))
                .isInstanceOf(WebhookSignatureInvalidError.class);
        }

        @Test
        void rejectsAMalformedBody() {
            assertThatThrownBy(() -> provider.verifyWebhook(new WebhookRequest(
                Map.of(XenditBillingProvider.TOKEN_HEADER, "callback-token"), "not json".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(WebhookSignatureInvalidError.class).hasMessageContaining("Malformed");
        }
    }

    @Nested
    class Manual {

        private final ManualBillingProvider provider = new ManualBillingProvider(JSON, "manual-token", "PHP");

        @Test
        void acceptsTheDeploymentToken() {
            assertThat(provider.verifyWebhook(new WebhookRequest(Map.of(ManualBillingProvider.TOKEN_HEADER, "manual-token"),
                "{\"id\":\"m1\",\"type\":\"manual.invoice.paid\"}".getBytes(StandardCharsets.UTF_8))).providerEventId()).isEqualTo("m1");
        }

        @Test
        void rejectsAWrongToken() {
            assertThatThrownBy(() -> provider.verifyWebhook(new WebhookRequest(Map.of(ManualBillingProvider.TOKEN_HEADER, "nope"), BODY)))
                .isInstanceOf(WebhookSignatureInvalidError.class).hasMessageContaining("manual webhook token");
        }

        /** With no token configured at all, nothing is accepted — not even an empty header. */
        @Test
        void refusesEverythingWhenNoTokenIsConfigured() {
            BillingProvider unconfigured = new ManualBillingProvider(JSON, "", "PHP");
            assertThatThrownBy(() -> unconfigured.verifyWebhook(new WebhookRequest(Map.of(ManualBillingProvider.TOKEN_HEADER, ""), BODY)))
                .isInstanceOf(WebhookSignatureInvalidError.class);
        }
    }
}
