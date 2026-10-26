package com.dia.ismdtoolbackend.config.security;

import com.dia.ismdtoolbackend.controller.dto.SearchResponseDto;
import com.dia.ismdtoolbackend.service.SearchService;
import com.dia.ismdtoolbackend.service.nkod.NkodCodelistService;
import com.dia.ismdtoolbackend.service.nkod.NkodCodelistSnapshotHolder;
import com.dia.ismdtoolbackend.testsupport.context.FullContextTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.testSecurityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The production {@link SecurityConfig} chains, driven through MockMvc.
 * <p>
 * The authenticated chain ends in {@code anyRequest().denyAll()}, and an anonymous request denied there also gets 401
 * from the entry point. So every protected route is checked three ways: anonymous gets 401 <em>without reaching a
 * controller</em>, an authenticated user reaches the named controller method and is not 403, and admin routes reject a
 * non-admin through {@code @PreAuthorize} (the handler is reached, then 403). An authenticated request denied by
 * {@code denyAll} never reaches a handler, so a removed matcher fails the second check.
 */
@FullContextTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
@TestPropertySource(properties = "full-context.db-name=local-security")
class SecurityFilterChainIntegrationTest {

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Autowired
    private MockMvc mockMvc;

    /** Stubbed, with its snapshot holder, so /api/codelist/nkod never reaches the live catalogue. */
    @MockitoBean
    private NkodCodelistService nkodCodelistService;

    @MockitoBean
    private NkodCodelistSnapshotHolder nkodCodelistSnapshotHolder;

    /** Bearer tokens are decoded here, so no request contacts the JWK set URI. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private SearchService searchService;

    /** A route of the authenticated chain and the controller method it reaches once matched. */
    record Route(HttpMethod method, String path, String handler) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    // ── Chain order ──────────────────────────────────────────────────────

    /** The first chain whose matcher accepts a request is the one that serves it: search → public → authenticated. */
    @Test
    void filterChains_serveSearchThenPublicThenAuthenticated() {
        assertThat(filterChainProxy.getFilterChains()).hasSize(3);

        assertThat(firstMatchingChain("/api/search")).isZero();
        assertThat(firstMatchingChain("/api/search/anything")).isZero();
        assertThat(firstMatchingChain("/api/ontology/list")).isEqualTo(1);
        assertThat(firstMatchingChain("/actuator/health")).isEqualTo(1);
        assertThat(firstMatchingChain("/api/user/me")).isEqualTo(2);
        assertThat(firstMatchingChain("/api/nonexistent")).isEqualTo(2);
    }

    private int firstMatchingChain(String path) {
        List<SecurityFilterChain> chains = filterChainProxy.getFilterChains();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        return IntStream.range(0, chains.size())
                .filter(i -> chains.get(i).matches(request))
                .findFirst()
                .orElse(-1);
    }

    // ── Public chain ─────────────────────────────────────────────────────

    // Mirrors the securityMatcher allowlist of SecurityConfig.publicSecurityFilterChain. A path dropped from the
    // allowlist falls to the authenticated chain and answers 401. Concrete paths exercise the wildcard patterns.
    @ParameterizedTest
    @ValueSource(strings = {
            // Actuator
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/info",
            // Ontology read endpoints
            "/api/ontology/test-slug/download",
            "/api/ontology/test-slug/detail",
            "/api/ontology/test-slug/validation-report",
            "/api/ontology/concepts",
            "/api/ontology/concepts/resolve",
            "/api/ontology/list",
            // Concept read endpoints
            "/api/concept/list",
            "/api/concept/test-slug/detail",
            // NKD read endpoints
            "/api/nkd/ontology/list",
            "/api/nkd/ontology/detail",
            "/api/nkd/ontology/all",
            "/api/nkd/ontology/download",
            "/api/nkd/concept/detail",
            // NKOD read endpoints
            "/api/nkod/dataset/all",
            "/api/nkod/dataset/detail",
            "/api/nkod/concept/datasets",
            // RPP read endpoints
            "/api/rpp/agenda/search",
            "/api/rpp/ais/search",
            // e-Sbírka read endpoints
            "/api/eli/law/search/grouped",
            "/api/eli/law/versions",
            "/api/eli/law/content",
            "/api/eli/resolve",
            // Codelist (wildcard)
            "/api/codelist/property-datatypes",
            "/api/codelist/nkod",
            // OpenAPI / Swagger
            "/v3/api-docs",
            "/swagger-ui/index.html",
            "/swagger-ui.html"
    })
    void publicEndpoints_accessibleWithoutAuth(String path) throws Exception {
        int status = mockMvc.perform(get(path)
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status)
                .as("Public endpoint %s should not require auth (got %d)", path, status)
                .isNotIn(401, 403);
    }

    // ── Authenticated chain ──────────────────────────────────────────────

    /** Every requestMatcher of SecurityConfig.authenticatedSecurityFilterChain open to any authenticated user. */
    static Stream<Route> authenticatedRoutes() {
        return Stream.of(
                new Route(HttpMethod.GET, "/api/user/me", "getCurrentUser"),
                new Route(HttpMethod.POST, "/api/ontology/upload", "uploadFromFile"),
                new Route(HttpMethod.POST, "/api/ontology/create", "createOntology"),
                new Route(HttpMethod.POST, "/api/ontology/check-iri", "checkIri"),
                new Route(HttpMethod.POST, "/api/ontology/create-with-concepts", "createWithConcepts"),
                new Route(HttpMethod.PATCH, "/api/ontology/test/edit", "editOntology"),
                new Route(HttpMethod.DELETE, "/api/ontology/test/delete", "deleteOntology"),
                new Route(HttpMethod.POST, "/api/ontology/test/validate", "validateOntology"),
                new Route(HttpMethod.POST, "/api/concept/test/create", "createConcept"),
                new Route(HttpMethod.POST, "/api/concept/test/create/bulk", "createConcepts"),
                new Route(HttpMethod.PATCH, "/api/concept/test/edit", "editConcept"),
                new Route(HttpMethod.DELETE, "/api/concept/test/delete", "deleteConcept"),
                new Route(HttpMethod.POST, "/api/concept/test/localcopy/test/update", "updateLocalCopy"),
                new Route(HttpMethod.DELETE, "/api/concept/test/localcopy/test", "removeLocalCopy"),
                new Route(HttpMethod.POST, "/api/concept/test/sync", "syncWorkingCopy"),
                new Route(HttpMethod.POST, "/api/comment/post", "postComment"),
                new Route(HttpMethod.DELETE, "/api/comment/test/delete", "deleteComment"),
                new Route(HttpMethod.GET, "/api/diagram/all", "getAllDiagrams"),
                new Route(HttpMethod.GET, "/api/diagram/test/list", "listForOntology"),
                new Route(HttpMethod.GET, "/api/diagram/usage/concept/test", "getConceptUsage"),
                new Route(HttpMethod.POST, "/api/diagram/test/create", "createDiagram"),
                new Route(HttpMethod.GET, "/api/diagram/test/5/detail", "getDiagram"),
                new Route(HttpMethod.PUT, "/api/diagram/test/5/layout", "saveLayout"),
                new Route(HttpMethod.PATCH, "/api/diagram/test/5/rename", "renameDiagram"),
                new Route(HttpMethod.POST, "/api/diagram/test/5/materialize", "materialize"),
                new Route(HttpMethod.DELETE, "/api/diagram/test/5", "deleteDiagram"),
                new Route(HttpMethod.POST, "/api/ai/accept-suggestion", "acceptSuggestions")
        );
    }

    /** The admin routes: matched by the authenticated chain, restricted by {@code @PreAuthorize("hasRole('ADMIN')")}. */
    static Stream<Route> adminRoutes() {
        return Stream.of(
                new Route(HttpMethod.POST, "/api/admin/reconciler/run", "run"),
                new Route(HttpMethod.GET, "/api/admin/reconciler/report", "lastReport"),
                new Route(HttpMethod.GET, "/api/admin/outbox/status", "status"),
                new Route(HttpMethod.GET, "/api/admin/outbox/failed", "failed"),
                new Route(HttpMethod.POST, "/api/admin/outbox/drain", "drain"),
                new Route(HttpMethod.POST, "/api/admin/outbox/retry/1", "retry")
        );
    }

    /** Admin routes that only read, so an admin can call them here without side effects. */
    static Stream<Route> adminReadRoutes() {
        return adminRoutes().filter(route -> route.method() == HttpMethod.GET);
    }

    static Stream<Route> allProtectedRoutes() {
        return Stream.concat(authenticatedRoutes(), adminRoutes());
    }

    @ParameterizedTest(name = "[{index}] anonymous {0}")
    @MethodSource("allProtectedRoutes")
    void protectedRoute_anonymous_rejectedBeforeController(Route route) throws Exception {
        MvcResult result = mockMvc.perform(requestFor(route)).andReturn();

        assertThat(result.getResponse().getStatus()).as("%s anonymous", route).isEqualTo(401);
        assertThat(result.getHandler()).as("%s anonymous must not reach a controller", route).isNull();
    }

    @ParameterizedTest(name = "[{index}] user {0}")
    @MethodSource("authenticatedRoutes")
    void authenticatedRoute_user_isMatchedAndReachesController(Route route) throws Exception {
        MvcResult result = mockMvc.perform(requestFor(route).with(user("ROLE_USER"))).andReturn();

        assertReachedHandler(result, route);
        assertThat(result.getResponse().getStatus())
                .as("%s must be matched by a requestMatcher; 403 means denyAll rejected it", route)
                .isNotEqualTo(403);
    }

    @ParameterizedTest(name = "[{index}] admin {0}")
    @MethodSource("adminReadRoutes")
    void adminRoute_admin_isMatchedAndAllowed(Route route) throws Exception {
        MvcResult result = mockMvc.perform(requestFor(route).with(user("ROLE_ADMIN"))).andReturn();

        assertReachedHandler(result, route);
        assertThat(result.getResponse().getStatus()).as("%s as admin", route).isNotIn(401, 403);
    }

    /** The handler is reached, so the 403 comes from {@code @PreAuthorize}, not from {@code denyAll}. */
    @ParameterizedTest(name = "[{index}] non-admin {0}")
    @MethodSource("adminRoutes")
    void adminRoute_nonAdmin_forbiddenByMethodSecurity(Route route) throws Exception {
        MvcResult result = mockMvc.perform(requestFor(route).with(user("ROLE_USER"))).andReturn();

        assertReachedHandler(result, route);
        assertThat(result.getResponse().getStatus()).as("%s as non-admin", route).isEqualTo(403);
    }

    private static AbstractMockHttpServletRequestBuilder<?> requestFor(Route route) {
        if ("uploadFromFile".equals(route.handler())) {
            return multipart(route.method(), route.path()).accept(MediaType.APPLICATION_JSON);
        }
        return request(route.method(), route.path())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
    }

    private static RequestPostProcessor user(String... roles) {
        SecurityUser user = new SecurityUser("testUser", "testUser", List.of(roles));
        return authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private static void assertReachedHandler(MvcResult result, Route route) {
        assertThat(result.getHandler())
                .as("%s must pass the filter chain and reach a controller", route)
                .isInstanceOfSatisfying(HandlerMethod.class, handler ->
                        assertThat(handler.getMethod().getName()).isEqualTo(route.handler()));
    }

    // ── Unmatched endpoints are denied ────────────────────────────────────

    @Test
    void unmatchedEndpoint_deniedWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/nonexistent")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    /** Deleted e-Sbírka endpoints: no matcher left, so denyAll answers before any 404. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/eli/law/search", "/api/eli/law/fragments"})
    @WithMockSecurityUser
    void removedEsbirkaEndpoint_deniedEvenWithAuth(String path) throws Exception {
        mockMvc.perform(get(path)
                        .with(testSecurityContext())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockSecurityUser
    void unmatchedEndpoint_deniedEvenWithAuth() throws Exception {
        mockMvc.perform(get("/api/nonexistent")
                        .with(testSecurityContext())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ── Bearer tokens through the real chains ─────────────────────────────

    @Test
    void authenticatedChain_validBearer_convertsTokenToSecurityUser() throws Exception {
        when(jwtDecoder.decode("valid-token")).thenReturn(jwt("valid-token", "user-123", "user"));

        mockMvc.perform(get("/api/user/me")
                        .header("Authorization", "Bearer valid-token")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("user-123"))
                .andExpect(jsonPath("$.data.displayName").value("jan.novak"));
    }

    @Test
    void authenticatedChain_invalidBearer_rejected() throws Exception {
        when(jwtDecoder.decode("bad-token")).thenThrow(new BadJwtException("signature invalid"));

        mockMvc.perform(get("/api/user/me")
                        .header("Authorization", "Bearer bad-token")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ── Search chain ─────────────────────────────────────────────────────

    @Test
    void searchChain_anonymous_reachesSearchWithoutPrincipal() throws Exception {
        when(searchService.search(anyString(), any(), any(), anyInt(), anyInt(), anyString(), any(), any(), any()))
                .thenReturn(new SearchResponseDto());

        mockMvc.perform(get("/api/search").param("q", "budova")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(searchService).search(eq("budova"), any(), any(), anyInt(), anyInt(), anyString(), any(), any(),
                isNull());
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void searchChain_validBearer_passesSecurityUserToSearch() throws Exception {
        when(jwtDecoder.decode("valid-token")).thenReturn(jwt("valid-token", "user-123", "user"));
        when(searchService.search(anyString(), any(), any(), anyInt(), anyInt(), anyString(), any(), any(), any()))
                .thenReturn(new SearchResponseDto());

        mockMvc.perform(get("/api/search").param("q", "budova")
                        .header("Authorization", "Bearer valid-token")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        ArgumentCaptor<SecurityUser> principal = ArgumentCaptor.forClass(SecurityUser.class);
        verify(searchService).search(eq("budova"), any(), any(), anyInt(), anyInt(), anyString(), any(), any(),
                principal.capture());
        assertThat(principal.getValue()).isNotNull();
        assertThat(principal.getValue().getUserId()).isEqualTo("user-123");
    }

    /** An invalid token is rejected with the JSON error body instead of degrading to anonymous. */
    @Test
    void searchChain_invalidBearer_401WithJsonError() throws Exception {
        when(jwtDecoder.decode("bad-token")).thenThrow(new BadJwtException("signature invalid"));

        mockMvc.perform(get("/api/search").param("q", "budova")
                        .header("Authorization", "Bearer bad-token")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));

        verify(searchService, never())
                .search(anyString(), any(), any(), anyInt(), anyInt(), anyString(), any(), any(), any());
    }

    private static Jwt jwt(String tokenValue, String subject, String... realmRoles) {
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .claim("sub", subject)
                .claim("preferred_username", "jan.novak")
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .build();
    }
}
