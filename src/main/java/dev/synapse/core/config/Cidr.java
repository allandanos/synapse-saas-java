package dev.synapse.core.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * A parsed CIDR block (reference: Python's {@code ipaddress.ip_network}).
 *
 * <p>Used for {@code SYNAPSE_TRUSTED_PROXIES}: only literals are accepted, so
 * nothing here ever performs a DNS lookup on attacker-controlled input.
 */
public final class Cidr {

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    private final byte[] network;
    private final int prefixBits;

    private Cidr(byte[] network, int prefixBits) {
        this.network = network;
        this.prefixBits = prefixBits;
    }

    /** {@code 10.0.0.0/8}, {@code 192.168.1.1} (host ⇒ /32), {@code fd00::/8}. */
    public static Cidr parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        int slash = text.indexOf('/');
        String host = slash < 0 ? text : text.substring(0, slash);
        byte[] address = address(host);
        if (address == null) {
            throw new IllegalArgumentException("Not a valid CIDR or address: " + raw);
        }
        int bits = address.length * 8;
        int prefix = bits;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(text.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not a valid CIDR or address: " + raw);
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("Not a valid CIDR or address: " + raw);
            }
        }
        return new Cidr(address, prefix);
    }

    /** The raw bytes of an IP literal, or {@code null} when {@code host} is not one. */
    public static byte[] address(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        var matcher = IPV4.matcher(bare);
        if (matcher.matches()) {
            byte[] octets = new byte[4];
            for (int i = 0; i < 4; i++) {
                int value = Integer.parseInt(matcher.group(i + 1));
                if (value > 255) {
                    return null;
                }
                octets[i] = (byte) value;
            }
            return octets;
        }
        if (bare.indexOf(':') < 0 || !IPV6_CHARS.matcher(bare).matches()) {
            return null; // a hostname: never resolved here
        }
        try {
            return InetAddress.getByName(bare).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    public boolean contains(byte[] address) {
        if (address == null || address.length != network.length) {
            return false;
        }
        int fullBytes = prefixBits / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (address[i] != network[i]) {
                return false;
            }
        }
        int remaining = prefixBits % 8;
        if (remaining == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remaining)) & 0xFF;
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    public boolean contains(String host) {
        return contains(address(host));
    }
}
