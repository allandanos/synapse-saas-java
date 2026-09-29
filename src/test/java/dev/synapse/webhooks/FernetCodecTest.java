package dev.synapse.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Fernet at rest, and the interop that lets any port deliver another's endpoints. */
class FernetCodecTest {

    private static final String SECRET_KEY = "dev-only-secret-key-change-me-32-bytes-minimum!";
    /** Produced by the reference's `cryptography` Fernet with the same SYNAPSE_SECRET_KEY. */
    private static final String REFERENCE_TOKEN =
        "gAAAAABquyZ44qVOXYLin0dwkTSm-2XlS3lLptdpFn3pNcx4AXg78PTlezekJqvdd2Gx81BQazO-mMpZBp11Yo8dEr3fzmK4fqEIDHRnUA6XsDf0erB183o=";
    private static final String REFERENCE_PLAINTEXT = "whsec_reference_interop_secret";

    private final FernetCodec codec = new FernetCodec(SECRET_KEY);

    @Test
    void decryptsATokenWrittenByTheReferenceImplementation() {
        assertThat(codec.decrypt(REFERENCE_TOKEN.getBytes(StandardCharsets.US_ASCII))).isEqualTo(REFERENCE_PLAINTEXT);
    }

    @Test
    void roundTripsASecret() {
        byte[] token = codec.encrypt("whsec_abc123");
        assertThat(new String(token, StandardCharsets.US_ASCII)).startsWith("gAAAAA"); // 0x80 + timestamp
        assertThat(codec.decrypt(token)).isEqualTo("whsec_abc123");
    }

    @Test
    void producesADifferentTokenEveryTime() {
        assertThat(codec.encrypt("same")).isNotEqualTo(codec.encrypt("same"));
    }

    @Test
    void refusesATokenSignedWithAnotherKey() {
        byte[] token = new FernetCodec("a-different-secret").encrypt("whsec_abc123");
        assertThatThrownBy(() -> codec.decrypt(token)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("signature mismatch");
    }

    @Test
    void refusesATamperedCiphertext() {
        byte[] raw = Base64.getUrlDecoder().decode(new String(codec.encrypt("whsec_abc123"), StandardCharsets.US_ASCII));
        raw[30] ^= 0x01;
        byte[] tampered = Base64.getUrlEncoder().encode(raw);
        assertThatThrownBy(() -> codec.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesSomethingThatIsNotAFernetToken() {
        byte[] token = Base64.getUrlEncoder().encode("not a fernet token at all, just bytes".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.decrypt(token)).isInstanceOf(IllegalStateException.class);
    }
}
