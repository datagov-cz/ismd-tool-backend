package com.dia.ismdtoolbackend.ratelimit;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.SearchResponseDto;
import com.dia.ismdtoolbackend.service.EsbirkaService;
import com.dia.ismdtoolbackend.service.SearchService;
import com.dia.ismdtoolbackend.testsupport.context.FullContextTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The limiter in the production security chains: the public chain, the search chain, and how callers are told apart. */
@FullContextTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "full-context.db-name=rate-limit-chains",
        "rate-limit.mode=ENFORCE",
        "rate-limit.trusted-proxies=127.0.0.0/8,198.51.100.20",
        "rate-limit.groups.esbirka.limit=2",
        "rate-limit.groups.search.limit=2"
})
class RateLimitChainIntegrationTest {

    private static final String LAW = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2000/1";
    private static final String APPGW = "198.51.100.20";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private EsbirkaService esbirkaService;
    @MockitoBean private SearchService searchService;
    @MockitoBean private JwtDecoder jwtDecoder;

    @BeforeEach
    void stubs() {
        when(esbirkaService.getVersions(anyString())).thenReturn(List.of());
        when(searchService.search(anyString(), any(), any(), anyInt(), anyInt(), anyString(), any(), any(), any()))
                .thenReturn(new SearchResponseDto());
    }

    private static MockHttpServletRequestBuilder versions(String client) {
        return get("/api/eli/law/versions").param("lawIri", LAW)
                .header(ClientIpResolver.HEADER, client + ", " + APPGW);
    }

    private static MockHttpServletRequestBuilder search(String client) {
        return get("/api/search").param("q", "budova").header(ClientIpResolver.HEADER, client + ", " + APPGW);
    }

    @Test
    void publicChain_theThirdCallFromOneClientGets429WithTheErrorBody() throws Exception {
        mockMvc.perform(versions("192.0.2.1")).andExpect(status().isOk());
        mockMvc.perform(versions("192.0.2.1")).andExpect(status().isOk());

        mockMvc.perform(versions("192.0.2.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));

        mockMvc.perform(versions("192.0.2.2")).andExpect(status().isOk());
    }

    @Test
    void searchChain_anonymousCallersAreToldApartByAddress() throws Exception {
        mockMvc.perform(search("192.0.2.11")).andExpect(status().isOk());
        mockMvc.perform(search("192.0.2.11")).andExpect(status().isOk());
        mockMvc.perform(search("192.0.2.11")).andExpect(status().isTooManyRequests());

        mockMvc.perform(search("192.0.2.12")).andExpect(status().isOk());
    }

    @Test
    void searchChain_signedInUsersHaveABudgetEachWhateverTheirAddress() throws Exception {
        mockMvc.perform(search("192.0.2.21").with(user("alice"))).andExpect(status().isOk());
        mockMvc.perform(search("192.0.2.21").with(user("alice"))).andExpect(status().isOk());
        mockMvc.perform(search("192.0.2.22").with(user("alice"))).andExpect(status().isTooManyRequests());

        mockMvc.perform(search("192.0.2.21").with(user("bob"))).andExpect(status().isOk());
    }

    @Test
    void anEndpointInNoGroupIsNotLimited() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(get("/actuator/health").header(ClientIpResolver.HEADER, "192.0.2.31, " + APPGW))
                    .andExpect(status().is(org.hamcrest.Matchers.not(429)));
        }
    }

    private static RequestPostProcessor user(String sub) {
        SecurityUser user = new SecurityUser(sub, sub, List.of("ROLE_USER"));
        return authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }
}
