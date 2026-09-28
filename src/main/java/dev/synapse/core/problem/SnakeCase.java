package dev.synapse.core.problem;

/** {@code displayName} → {@code display_name} (Jackson field names back to the wire names in {@code loc}). */
final class SnakeCase {

    private SnakeCase() {}

    static String of(String camel) {
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
