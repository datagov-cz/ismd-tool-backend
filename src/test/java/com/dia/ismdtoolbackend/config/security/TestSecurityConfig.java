package com.dia.ismdtoolbackend.config.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.testSecurityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Test security configuration that replaces production OAuth2 setup.
 * <p>
 * Key differences from production SecurityConfig:
 * - No OAuth2 Resource Server (JWT validation disabled)
 * - No OAuth2 Login (Keycloak integration disabled)
 * - All requests are permitted (authentication provided via @WithMockSecurityUser)
 * - Method security (@PreAuthorize) still enabled for testing authorization logic
 * <p>
 * Usage in tests:
 * - Use @Import(TestSecurityConfig.class) in @WebMvcTest
 * - Use @WithMockSecurityUser to inject mock authenticated users
 */
@TestConfiguration
@Profile("junit")
@EnableWebSecurity
@EnableMethodSecurity  // Required for @PreAuthorize to work in tests
public class TestSecurityConfig {

    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                );

        return http.build();
    }

    @Bean
    public MockMvcBuilderCustomizer testSecurityContextMockMvcBuilderCustomizer() {
        return builder -> builder.defaultRequest(get("/").with(testSecurityContext()));
    }
}