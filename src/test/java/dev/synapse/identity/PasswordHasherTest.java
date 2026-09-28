package dev.synapse.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashesAreArgon2idWithTheReferenceParameters() {
        String hash = hasher.hash("conformance-password-12345");
        assertThat(hash).startsWith("$argon2id$v=19$m=65536,t=3,p=4$");
        assertThat(hasher.verify("conformance-password-12345", hash)).isTrue();
        assertThat(hasher.verify("wrong-password-1", hash)).isFalse();
        assertThat(hasher.hash("conformance-password-12345")).isNotEqualTo(hash); // fresh salt every time
    }

    @Test
    void verifiesHashesProducedByThePythonReference() {
        // argon2-cffi output for "oracle-admin-password-1" (taken from a reference database)
        String reference = "$argon2id$v=19$m=65536,t=3,p=4$SXuPXzC72oV8w2tCTq7A9w$+MfgRMddHZvFAZxmfr+PFRcJhK1Q70CdFBOFVue2/qo";
        assertThat(hasher.verify("oracle-admin-password-1", reference)).isTrue();
        assertThat(hasher.verify("oracle-admin-password-2", reference)).isFalse();
    }

    @Test
    void dummyHashBurnsCpuWithoutMatchingAndGarbageNeverThrows() {
        assertThat(hasher.verify("anything", PasswordHasher.DUMMY_HASH)).isFalse();
        assertThat(hasher.verify("anything", "not-a-hash")).isFalse();
        assertThat(hasher.verify("anything", null)).isFalse();
    }
}
