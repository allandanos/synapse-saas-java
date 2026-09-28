package dev.synapse.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.synapse.core.db.RlsGucs;
import dev.synapse.core.errors.NotFoundError;
import dev.synapse.identity.JwtCodec;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

/** The header → subdomain → JWT-claim resolution order, without a database. */
class TenantResolverReferenceTest {

    private final JwtCodec jwt = new JwtCodec("unit-test-secret-key-at-least-32-bytes-long!!", 900);
    private final TenantResolver resolver = new TenantResolver(
        mock(OrganizationRepository.class), mock(MembershipRepository.class), jwt, mock(RlsGucs.class), mock(TransactionTemplate.class));

    @Test
    void xOrgIdWinsAndAcceptsThePython32HexForm() {
        UUID id = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Org-Id", id.toString().replace("-", ""));
        request.addHeader("X-Org-Slug", "ignored");
        assertThat(resolver.resolveOrgReference(request)).isEqualTo(id);
    }

    @Test
    void invalidXOrgIdIsA404Problem() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Org-Id", "nope");
        assertThatThrownBy(() -> resolver.resolveOrgReference(request)).isInstanceOf(NotFoundError.class).hasMessage("Invalid X-Org-Id header");
    }

    @Test
    void slugHeaderThenSubdomainThenJwtClaim() {
        MockHttpServletRequest slug = new MockHttpServletRequest();
        slug.addHeader("X-Org-Slug", "acme");
        assertThat(resolver.resolveOrgReference(slug)).isEqualTo("acme");

        MockHttpServletRequest host = new MockHttpServletRequest();
        host.addHeader("Host", "acme.localhost:8080");
        assertThat(resolver.resolveOrgReference(host)).isEqualTo("acme");

        MockHttpServletRequest ip = new MockHttpServletRequest();
        ip.addHeader("Host", "127.0.0.1:8080");
        assertThat(resolver.resolveOrgReference(ip)).isNull(); // IP literals never resolve the slug "127"
        MockHttpServletRequest ipv6 = new MockHttpServletRequest();
        ipv6.addHeader("Host", "[::1]:8080");
        assertThat(resolver.resolveOrgReference(ipv6)).isNull();

        MockHttpServletRequest www = new MockHttpServletRequest();
        www.addHeader("Host", "www.example.com");
        assertThat(resolver.resolveOrgReference(www)).isNull();

        UUID org = UUID.randomUUID();
        MockHttpServletRequest claim = new MockHttpServletRequest();
        claim.addHeader("Host", "localhost:8080");
        claim.addHeader("Authorization", "Bearer " + jwt.createAccessToken(UUID.randomUUID(), "a@example.com", org, false));
        assertThat(resolver.resolveOrgReference(claim)).isEqualTo(org);

        MockHttpServletRequest noClaim = new MockHttpServletRequest();
        noClaim.addHeader("Authorization", "Bearer " + jwt.createAccessToken(UUID.randomUUID(), "a@example.com", null, false));
        assertThat(resolver.resolveOrgReference(noClaim)).isNull();

        MockHttpServletRequest apiKey = new MockHttpServletRequest();
        apiKey.addHeader("Authorization", "Bearer sk_whatever");
        assertThat(resolver.resolveOrgReference(apiKey)).isNull();
    }
}
