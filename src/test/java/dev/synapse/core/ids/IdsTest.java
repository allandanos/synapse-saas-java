package dev.synapse.core.ids;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void uuidV7HasVersionSevenRfcVariantAndAnEmbeddedTimestamp() {
        long before = System.currentTimeMillis();
        UUID id = Ids.uuidV7();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(Ids.uuidV7Millis(id)).isBetween(before, System.currentTimeMillis());
        assertThat(Ids.uuidV7()).isNotEqualTo(id);
    }

    @Test
    void slugifyCollapsesAndTrims() {
        assertThat(Ids.slugify("  Acme Corp!! 2026 ")).isEqualTo("acme-corp-2026");
        assertThat(Ids.slugify("Org 1790595327")).isEqualTo("org-1790595327");
        assertThat(Ids.slugify("---")).isEmpty();
        assertThat(Ids.slugify("a".repeat(60))).hasSize(48);
    }

    @Test
    void validSlugsExcludeReservedNamesAndBadShapes() {
        assertThat(Ids.isValidSlug("org-1")).isTrue();
        assertThat(Ids.isValidSlug("admin")).isFalse();
        assertThat(Ids.isValidSlug("-bad")).isFalse();
        assertThat(Ids.isValidSlug("Bad_Slug")).isFalse();
        assertThat(Ids.isValidSlug("ab")).isFalse(); // reference pattern needs 1 or 3+ chars
        assertThat(Ids.isValidSlug("a")).isTrue();
    }

    @Test
    void uniqueSlugAppendsSixHexChars() {
        assertThat(Ids.uniqueSlug("Org 1790595327")).matches("org-1790595327-[0-9a-f]{6}");
        assertThat(Ids.uniqueSlug("!!!")).matches("org-[0-9a-f]{6}");
    }

    @Test
    void parseLenientAcceptsThirtyTwoHexLikePython() {
        UUID expected = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        assertThat(Ids.parseLenient("0123456789abcdef0123456789abcdef")).isEqualTo(expected);
        assertThat(Ids.parseLenient(expected.toString())).isEqualTo(expected);
        assertThatThrownBy(() -> Ids.parseLenient("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secretsAreUrlSafeAndHashesStable() {
        assertThat(Secrets.urlsafeToken(32)).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(Secrets.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Secrets.constantTimeEquals("a", "a")).isTrue();
        assertThat(Secrets.constantTimeEquals("a", "b")).isFalse();
    }
}
