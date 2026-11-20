package com.dia.ismdtoolbackend.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The client address behind the proxy chain, read right to left past trusted proxies. */
class ClientIpResolverTest {

    private static final String NGINX = "203.0.113.10";
    private static final String APPGW = "198.51.100.20";
    private static final String CLIENT = "192.0.2.55";
    private static final String INTERNAL_PEER = "100.100.1.7";

    private static ClientIpResolver resolver(String... extraTrusted) {
        RateLimitConfig config = new RateLimitConfig();
        config.getTrustedProxies().addAll(List.of(extraTrusted));
        return new ClientIpResolver(config);
    }

    private static MockHttpServletRequest request(String peer, String xff) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (xff != null) {
            request.addHeader(ClientIpResolver.HEADER, xff);
        }
        return request;
    }

    @Test
    void withNginxInFront_theClientIsTheEntryBeforeNginx() {
        String xff = CLIENT + ", " + NGINX + ":51234, " + APPGW + ", 10.0.3.4";

        assertThat(resolver(NGINX, APPGW).resolve(request(INTERNAL_PEER, xff))).isEqualTo(CLIENT);
    }

    @Test
    void afterNginxIsGone_theSameRuleFindsTheClientWithoutNginxListed() {
        String xff = CLIENT + ":40100, " + APPGW;

        assertThat(resolver(APPGW).resolve(request(INTERNAL_PEER, xff))).isEqualTo(CLIENT);
    }

    @Test
    void whatTheClientPutsInTheHeaderIsNeverUsed() {
        String xff = "1.1.1.1, 8.8.8.8, " + CLIENT + ", " + NGINX + ", " + APPGW;

        assertThat(resolver(NGINX, APPGW).resolve(request(INTERNAL_PEER, xff))).isEqualTo(CLIENT);
    }

    @Test
    void anUnlistedProxyIsTakenForTheClient_soEveryUserBehindItShares() {
        String xff = CLIENT + ", " + NGINX + ", " + APPGW;

        assertThat(resolver(APPGW).resolve(request(INTERNAL_PEER, xff))).isEqualTo(NGINX);
    }

    @Test
    void anUntrustedPeerCannotVouchForAnyone() {
        assertThat(resolver().resolve(request(CLIENT, "9.9.9.9"))).isEqualTo(CLIENT);
    }

    @Test
    void withoutTheHeader_thePeerIsTheCaller() {
        assertThat(resolver().resolve(request(INTERNAL_PEER, null))).isEqualTo(INTERNAL_PEER);
    }

    @Test
    void aMalformedEntryOrAnAllTrustedChainFallsBackToThePeer() {
        assertThat(resolver().resolve(request(INTERNAL_PEER, CLIENT + ", not-an-ip"))).isEqualTo(INTERNAL_PEER);
        assertThat(resolver().resolve(request(INTERNAL_PEER, "10.0.0.1, 10.0.0.2"))).isEqualTo(INTERNAL_PEER);
    }

    @Test
    void ipv6EntriesWithBracketsAndPortsAreRead() {
        assertThat(resolver().resolve(request(INTERNAL_PEER, "[2001:db8::7]:4430, 10.0.0.2")))
                .isEqualTo("2001:db8::7");
        assertThat(resolver().resolve(request(INTERNAL_PEER, "2001:db8::8"))).isEqualTo("2001:db8::8");
    }
}
