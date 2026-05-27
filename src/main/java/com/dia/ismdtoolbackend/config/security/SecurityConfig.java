package com.dia.ismdtoolbackend.config.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

@Slf4j
@Configuration
@Profile("!test")
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthenticationConverter jwtAuthenticationConverter;
    private final org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource;

    @Value("${spring.security.oauth2.client.registration.keycloak.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.keycloak.client-secret}")
    private String clientSecret;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    /**
     * Validates Keycloak configuration at application startup.
     * Fails fast if required configuration is missing.
     */
    @PostConstruct
    public void validateKeycloakConfiguration() {
        log.info("Validating Keycloak OAuth2 configuration...");

        if (!StringUtils.hasText(clientId)) {
            throw new IllegalStateException(
                    "Keycloak client ID is not configured. " +
                    "Set KEYCLOAK_CLIENT_ID environment variable or application property.");
        }

        if (!StringUtils.hasText(clientSecret)) {
            throw new IllegalStateException(
                    "Keycloak client secret is not configured. " +
                    "Set KEYCLOAK_CLIENT_SECRET environment variable or application property.");
        }

        if (!StringUtils.hasText(issuerUri)) {
            throw new IllegalStateException(
                    "Keycloak issuer URI is not configured. " +
                    "Set KEYCLOAK_ISSUER_URI environment variable or application property.");
        }

        log.info("Keycloak configuration validated successfully.");
    }

    /**
     * Search security filter chain for /api/search/** endpoints.
     * Allows anonymous access but processes JWT if present (to populate SecurityUser).
     * <p>
     * Order(0) ensures this chain is evaluated before all others.
     * Anonymous requests pass through without 401; authenticated requests get SecurityUser populated.
     *
     * @param http HttpSecurity configuration
     * @return configured SecurityFilterChain for search endpoints
     * @throws Exception if configuration fails
     */
    @Bean
    @Order(0)
    public SecurityFilterChain searchSecurityFilterChain(HttpSecurity http) throws Exception {
        log.info("Configuring search security filter chain (Order 0)...");

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .securityMatcher("/api/search/**")
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                // Process JWT if present — populates SecurityUser for authenticated users
                // Anonymous requests (no Authorization header) pass through without error
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint((request, response, authException) -> {
                            // If an Authorization header was provided but the token is invalid,
                            // return 401 instead of silently degrading to anonymous
                            String authHeader = request.getHeader("Authorization");
                            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                                log.warn("Invalid JWT token on search endpoint: {}", authException.getMessage());
                                response.setStatus(401);
                                response.setContentType("application/json");
                                response.getWriter().write(
                                        "{\"success\":false,\"message\":\"Invalid or expired authentication token\"}");
                            }
                            // No token — allow anonymous access to proceed
                        })
                );

        log.info("Search security filter chain configured successfully");
        return http.build();
    }

    /**
     * Public security filter chain for unauthenticated endpoints.
     * Handles public download endpoint and actuator health/info endpoints.
     * <p>
     * Order(1) ensures this chain is evaluated after the search chain.
     * Requests matching these patterns bypass OAuth2 JWT validation entirely.
     *
     * @param http HttpSecurity configuration
     * @return configured SecurityFilterChain for public endpoints
     * @throws Exception if configuration fails
     */
    @Bean
    @Order(1)
    public SecurityFilterChain publicSecurityFilterChain(HttpSecurity http) throws Exception {
        log.info("Configuring public security filter chain (Order 1)...");

        http
                // CORS configuration
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // Only apply this chain to public endpoints
                .securityMatcher(
                        "/actuator/health",
                        "/actuator/health/*",
                        "/actuator/info",
                        "/api/ontology/*/download",
                        "/api/ontology/*/detail",
                        "/api/ontology/concepts",
                        "/api/ontology/concepts/resolve",
                        "/api/ontology/list",
                        "/api/concept/list",
                        "/api/concept/*/detail",
                        "/api/nkd/ontology/list",
                        "/api/nkd/ontology/detail",
                        "/api/nkd/ontology/all",
                        "/api/nkd/ontology/download",
                        "/api/nkd/concept/detail",
                        "/api/rpp/agenda/search",
                        "/api/rpp/ais/search",
                        "/api/eli/law/search",
                        "/api/eli/law/versions",
                        "/api/eli/law/fragments",
                        "/api/eli/resolve",
                        "/api/codelist/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html"
                )
                // Allow all requests to these endpoints
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                // Disable CSRF for stateless API
                .csrf(AbstractHttpConfigurer::disable)
                // Stateless session management
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                );

        log.info("Public security filter chain configured successfully");
        return http.build();
    }

    /**
     * Authenticated security filter chain for protected endpoints.
     * Handles all endpoints requiring OAuth2/JWT authentication.
     * <p>
     * Order(2) ensures this chain is evaluated after the public chain.
     * All requests not matched by the public chain will be processed here.
     *
     * @param http HttpSecurity configuration
     * @return configured SecurityFilterChain for authenticated endpoints
     * @throws Exception if configuration fails
     */
    @Bean
    @Order(2)
    public SecurityFilterChain authenticatedSecurityFilterChain(HttpSecurity http) throws Exception {
        log.info("Configuring authenticated security filter chain (Order 2)...");

        http
                // CORS configuration
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // Authorization rules for authenticated endpoints
                .authorizeHttpRequests(auth -> auth
                        // Explicitly configured authenticated endpoints
                        .requestMatchers(HttpMethod.GET, "/api/user/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/ontology/upload").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/ontology/create").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/ontology/*/edit").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/ontology/*/delete").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/ontology/*/validate").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/ontology/*/catalog-record").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/concept/*/create").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/concept/*/edit").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/concept/*/delete").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/comment/post").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/comment/*/delete").authenticated()
                        .anyRequest().denyAll()
                )
                // Disable CSRF for stateless JWT API
                .csrf(AbstractHttpConfigurer::disable)
                // Allow session creation for OAuth2 login flow (stores authentication after Keycloak redirect)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )
                // OAuth2 Login for browser-based authentication (Keycloak)
                .oauth2Login(oauth2 -> oauth2
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error=true")
                )
                // OAuth2 Resource Server for JWT validation
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                );

        log.info("Authenticated security filter chain configured successfully");
        return http.build();
    }
}
