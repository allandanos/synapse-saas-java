package dev.synapse.identity.ratelimit;

import dev.synapse.core.config.Cidr;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * The client address used for rate limiting — never spoofable by the client
 * (reference: {@code identity/rate_limit.py:_client_ip}, {@code _is_trusted_proxy}).
 *
 * <p>{@code X-Forwarded-For} is honoured only when the socket peer is a
 * configured trusted proxy, and then only back to the first hop that is NOT a
 * trusted proxy (walking right to left — each proxy appends the peer it saw).
 * With no trusted proxies configured the header is ignored outright.
 */
public final class ClientIp {

    public static final String UNKNOWN = "unknown";

    private ClientIp() {}

    public static List<Cidr> parse(List<String> cidrs) {
        List<Cidr> parsed = new ArrayList<>();
        for (String cidr : cidrs) {
            parsed.add(Cidr.parse(cidr));
        }
        return List.copyOf(parsed);
    }

    public static String of(HttpServletRequest request, List<Cidr> trusted) {
        String peer = request.getRemoteAddr();
        return of(peer, request.getHeader("X-Forwarded-For"), trusted);
    }

    public static String of(String peer, String forwardedFor, List<Cidr> trusted) {
        String client = peer == null || peer.isBlank() ? UNKNOWN : peer;
        if (trusted.isEmpty() || !isTrusted(client, trusted)) {
            return client;
        }
        List<String> hops = new ArrayList<>();
        if (forwardedFor != null) {
            for (String hop : forwardedFor.split(",")) {
                String trimmed = hop.trim();
                if (!trimmed.isEmpty()) {
                    hops.add(trimmed);
                }
            }
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            if (!isTrusted(hops.get(i), trusted)) {
                return hops.get(i);
            }
        }
        return client;
    }

    /** A hop that is not an IP literal is never trusted (and so stops the walk). */
    static boolean isTrusted(String host, List<Cidr> trusted) {
        byte[] address = Cidr.address(host);
        if (address == null) {
            return false;
        }
        return trusted.stream().anyMatch(cidr -> cidr.contains(address));
    }
}
