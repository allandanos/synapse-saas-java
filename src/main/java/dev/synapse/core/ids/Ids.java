package dev.synapse.core.ids;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ID generation and slug utilities (reference: {@code core/ids.py}).
 *
 * <p>UUIDv7 (time-ordered) for high-volume rows — outbox events, audit logs —
 * so B-tree locality matches insert order; UUIDv4 for low-volume entities.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern DASHES = Pattern.compile("-{2,}");
    private static final Pattern VALID_SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{1,46}[a-z0-9])?");

    public static final Set<String> RESERVED_SLUGS = Set.of(
        "www", "api", "app", "admin", "mail", "smtp", "ftp", "sftp", "ssh", "support", "help", "billing",
        "checkout", "pay", "login", "signin", "signup", "register", "logout", "static", "assets", "cdn",
        "docs", "status", "health", "platform", "system", "root", "synapse", "dashboard", "console", "portal");

    private Ids() {}

    /** RFC 9562 UUIDv7: 48-bit unix-ms timestamp + 74 random bits. */
    public static UUID uuidV7() {
        long tsMs = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long randB = RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL;
        long msb = ((tsMs & 0xFFFFFFFFFFFFL) << 16) | (0x7L << 12) | randA;
        long lsb = (0b10L << 62) | randB;
        return new UUID(msb, lsb);
    }

    public static UUID newUuid() {
        return UUID.randomUUID();
    }

    /** Milliseconds embedded in a UUIDv7. */
    public static long uuidV7Millis(UUID value) {
        return value.getMostSignificantBits() >>> 16;
    }

    public static String slugify(String text) {
        return slugify(text, 48);
    }

    /** Lowercase {@code [a-z0-9-]} slug, collapsed separators, trimmed, length-capped. */
    public static String slugify(String text, int maxLength) {
        String slug = NON_SLUG.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("-");
        slug = strip(slug, '-');
        slug = DASHES.matcher(slug).replaceAll("-");
        if (slug.length() > maxLength) {
            slug = slug.substring(0, maxLength);
        }
        return strip(slug, '-');
    }

    public static boolean isValidSlug(String slug) {
        return VALID_SLUG.matcher(slug).matches() && !RESERVED_SLUGS.contains(slug);
    }

    /** Slug with a short random suffix — used when the preferred slug is taken. */
    public static String uniqueSlug(String base) {
        byte[] bytes = new byte[3];
        RANDOM.nextBytes(bytes);
        String stem = slugify(base);
        if (stem.length() > 39) {
            stem = stem.substring(0, 39);
        }
        stem = strip(stem, '-');
        if (stem.isEmpty()) {
            stem = "org";
        }
        return stem + "-" + String.format("%02x%02x%02x", bytes[0], bytes[1], bytes[2]);
    }

    /** {@code UUID.fromString} that also accepts the 32-hex form Python's {@code UUID()} takes. */
    public static UUID parseLenient(String raw) {
        String s = raw.trim();
        if (s.length() == 32 && s.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            s = s.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
        }
        return UUID.fromString(s);
    }

    private static String strip(String s, char c) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == c) {
            start++;
        }
        while (end > start && s.charAt(end - 1) == c) {
            end--;
        }
        return s.substring(start, end);
    }
}
