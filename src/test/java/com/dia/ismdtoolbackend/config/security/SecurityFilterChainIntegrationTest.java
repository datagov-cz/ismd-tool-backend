package com.dia.ismdtoolbackend.config.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test verifying the SecurityFilterChain ordering and behavior.
 * <p>
 * Loads the real {@link SecurityConfig} (non-test profile) to verify:
 * - Correct @Order annotations on both filter chains
 * - Public endpoints are accessible without authentication
 * - Protected endpoints reject unauthenticated requests
 * - Unmatched endpoints are denied
 * <p>
 * This test guards against accidental @Order swaps or securityMatcher removal
 * that could silently break authentication.
 */
@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.security.oauth2.client.registration.keycloak.client-id=test-client-id",
        "spring.security.oauth2.client.registration.keycloak.client-secret=test-client-secret",
        "spring.security.oauth2.client.registration.keycloak.provider=keycloak-test",
        "spring.security.oauth2.client.provider.keycloak-test.authorization-uri=http://localhost/auth",
        "spring.security.oauth2.client.provider.keycloak-test.token-uri=http://localhost/token",
        "spring.security.oauth2.client.provider.keycloak-test.user-info-uri=http://localhost/userinfo",
        "spring.security.oauth2.client.provider.keycloak-test.jwk-set-uri=http://localhost/jwks",
        "spring.security.oauth2.client.provider.keycloak-test.user-name-attribute=preferred_username",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks",
        "spring.datasource.url=jdbc:h2:mem:testdb-local-security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;INIT=CREATE SCHEMA IF NOT EXISTS ismd_schema",
        "spring.datasource.username=sa",
        "spring.datasource.password=password",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database=h2",
        "spring.liquibase.liquibase-schema=PUBLIC"
})
class SecurityFilterChainIntegrationTest {

    @Autowired
    private List<SecurityFilterChain> filterChains;

    @Autowired
    private MockMvc mockMvc;

    // ── Bean introspection tests ──────────────────────────────────────────

    @Test
    void contextLoads_threeSecurityFilterChainBeans() {
        assertThat(filterChains).hasSize(3);
    }

    @Test
    void publicChain_hasOrder1() throws NoSuchMethodException {
        Method method = SecurityConfig.class.getMethod(
                "publicSecurityFilterChain",
                org.springframework.security.config.annotation.web.builders.HttpSecurity.class
        );
        Order order = method.getAnnotation(Order.class);

        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(1);
    }

    @Test
    void authenticatedChain_hasOrder2() throws NoSuchMethodException {
        Method method = SecurityConfig.class.getMethod(
                "authenticatedSecurityFilterChain",
                org.springframework.security.config.annotation.web.builders.HttpSecurity.class
        );
        Order order = method.getAnnotation(Order.class);

        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(2);
    }

    // ── Public endpoints accessible without auth ──────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/info",
            "/api/ontology/test-slug/download",
            "/api/ontology/test-slug/detail",
            "/api/ontology/list",
            "/api/concept/list",
            "/v3/api-docs",
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

    // ── Protected endpoints reject unauthenticated requests ───────────────

    @ParameterizedTest
    @MethodSource("protectedEndpoints")
    void protectedEndpoints_rejectUnauthenticatedRequests(String method, String path) throws Exception {
        var requestBuilder = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PATCH" -> patch(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        };

        int status = mockMvc.perform(requestBuilder
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status)
                .as("Protected endpoint %s %s should reject unauthenticated requests (got %d)", method, path, status)
                .isIn(401, 302);
    }

    static Stream<Arguments> protectedEndpoints() {
        return Stream.of(
                Arguments.of("GET", "/api/user/me"),
                Arguments.of("POST", "/api/ontology/upload"),
                Arguments.of("POST", "/api/ontology/create"),
                Arguments.of("PATCH", "/api/ontology/test/edit"),
                Arguments.of("DELETE", "/api/ontology/test/delete"),
                Arguments.of("POST", "/api/concept/create"),
                Arguments.of("PATCH", "/api/concept/test/edit"),
                Arguments.of("DELETE", "/api/concept/test/delete"),
                Arguments.of("POST", "/api/comment/post"),
                Arguments.of("DELETE", "/api/comment/test/delete")
        );
    }

    // ── Unmatched endpoints are denied ────────────────────────────────────

    @Test
    void unmatchedEndpoint_deniedWithoutAuth() throws Exception {
        int status = mockMvc.perform(get("/api/nonexistent")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status)
                .as("Unmatched endpoint should be denied (got %d)", status)
                .isIn(401, 403);
    }

    @Test
    @WithMockSecurityUser
    void unmatchedEndpoint_deniedEvenWithAuth() throws Exception {
        mockMvc.perform(get("/api/nonexistent")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
