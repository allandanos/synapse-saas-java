package dev.synapse.identity.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.synapse.core.config.Cidr;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Rate-limit client identification: X-Forwarded-For is only trusted from
 * trusted proxies (reference: {@code tests/unit/identity/test_client_ip.py}).
 */
class ClientIpTest {

    private static String ip(String peer, String xff, String... cidrs) {
        return ClientIp.of(peer, xff, ClientIp.parse(List.of(cidrs)));
    }

    @Nested
    @DisplayName("without trusted proxies")
    class NoTrustedProxies {

        @Test
        void theHeaderIsIgnored() {
            // An attacker rotating X-Forwarded-For must still be bucketed by the socket peer
            assertThat(ip("203.0.113.9", "1.1.1.1")).isEqualTo("203.0.113.9");
        }

        @Test
        void aMissingPeerIsUnknown() {
            assertThat(ip(null, "1.1.1.1")).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("with trusted proxies")
    class TrustedProxies {

        @Test
        void rightmostUntrustedHopWins() {
            // client → proxyA(10.0.0.5) → proxyB(10.0.0.6) → us; each proxy appends its peer
            assertThat(ip("10.0.0.6", "198.51.100.7, 10.0.0.5", "10.0.0.0/8", "192.168.0.0/16"))
                .isEqualTo("198.51.100.7");
        }

        @Test
        void aClientSpoofedPrefixIsSkipped() {
            // The attacker sent "X-Forwarded-For: 1.1.1.1"; the proxy appended the real peer
            assertThat(ip("10.0.0.6", "1.1.1.1, 198.51.100.7", "10.0.0.0/8")).isEqualTo("198.51.100.7");
        }

        @Test
        void anUntrustedPeerIgnoresTheHeader() {
            assertThat(ip("203.0.113.9", "1.1.1.1", "10.0.0.0/8")).isEqualTo("203.0.113.9");
        }

        @Test
        void allHopsTrustedFallsBackToThePeer() {
            assertThat(ip("10.0.0.6", "10.0.0.5", "10.0.0.0/8")).isEqualTo("10.0.0.6");
        }

        @Test
        void garbageHopsAreNotTrusted() {
            assertThat(ip("10.0.0.6", "not-an-ip, 10.0.0.5", "10.0.0.0/8")).isEqualTo("not-an-ip");
        }

        @Test
        void ipv6PeersAndBlocksWork() {
            assertThat(ip("fd00::6", "198.51.100.7, fd00::5", "fd00::/8")).isEqualTo("198.51.100.7");
        }
    }

    @Nested
    @DisplayName("CIDR parsing")
    class Parsing {

        @Test
        void hostAddressesBecomeFullPrefixes() {
            assertThat(Cidr.parse("192.168.1.1").contains("192.168.1.1")).isTrue();
            assertThat(Cidr.parse("192.168.1.1").contains("192.168.1.2")).isFalse();
        }

        @Test
        void nonByteAlignedPrefixes() {
            Cidr cidr = Cidr.parse("10.0.0.0/12");
            assertThat(cidr.contains("10.15.255.255")).isTrue();
            assertThat(cidr.contains("10.16.0.1")).isFalse();
        }

        @Test
        void garbageIsRejected() {
            assertThatThrownBy(() -> Cidr.parse("not-a-cidr")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-a-cidr");
            assertThatThrownBy(() -> Cidr.parse("10.0.0.0/64")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void hostnamesAreNeverResolved() {
            assertThat(Cidr.address("localhost")).isNull();
            assertThat(Cidr.address("example.com")).isNull();
        }
    }
}
