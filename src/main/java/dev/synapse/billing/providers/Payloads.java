package dev.synapse.billing.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.core.errors.WebhookSignatureInvalidError;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/** Shared provider helpers: body parsing, nested lookups, token minting, money formatting. */
public final class Payloads {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, String> SYMBOLS = Map.of("PHP", "₱", "USD", "$", "EUR", "€");

    private Payloads() {}

    /** A malformed body is a verification failure, exactly as in the reference. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(ObjectMapper json, byte[] body, String label) {
        try {
            Map<String, Object> parsed = json.readValue(body, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            throw new WebhookSignatureInvalidError("Malformed " + label + " webhook body");
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** Nested lookup: {@code at(parsed, "data", "object")}. */
    public static Map<String, Object> at(Map<String, Object> root, String... path) {
        Map<String, Object> current = root;
        for (String key : path) {
            current = map(current.get(key));
        }
        return current;
    }

    public static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static Long integer(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number n ? n.longValue() : null;
    }

    public static String upperOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).toUpperCase(Locale.ROOT);
        return text.isEmpty() ? null : text;
    }

    /** Unix seconds → instant, or null when the field is absent or not an integer. */
    public static Instant epochSeconds(Map<String, Object> source, String key) {
        Long seconds = integer(source, key);
        return seconds == null ? null : Instant.ofEpochSecond(seconds);
    }

    /** {@code secrets.token_hex(n)} equivalent: 2n lowercase hex characters. */
    public static String tokenHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        StringBuilder out = new StringBuilder(bytes * 2);
        for (byte b : buffer) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /** The reference's `_format_money`: currency symbol plus grouped major units. */
    public static String money(long cents, String currency) {
        String symbol = SYMBOLS.getOrDefault(currency, currency + " ");
        return symbol + String.format(Locale.US, "%,.2f", cents / 100.0);
    }
}
