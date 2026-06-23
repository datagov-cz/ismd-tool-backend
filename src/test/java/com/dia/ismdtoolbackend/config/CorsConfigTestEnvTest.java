package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.config.cors.CorsConfig;
import com.dia.ismdtoolbackend.config.cors.CorsConfigUtil;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test for {@link CorsConfigUtil} when we simulate test environment.
 *
 * @see CorsConfig
 */
@SpringBootTest
@ActiveProfiles("junit")
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
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
        "spring.datasource.url=jdbc:h2:mem:testdb-testenv;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;INIT=CREATE SCHEMA IF NOT EXISTS ismd_schema",
        "spring.datasource.username=sa",
        "spring.datasource.password=password",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database=h2",
        "spring.liquibase.liquibase-schema=PUBLIC"
})
class CorsConfigTestEnvTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testFrontendDomain() throws Exception {
        String testOrigin = "https://www.domain.org";

        mockMvc.perform(get("/actuator/health")
                        .header("Origin", testOrigin))
                .andExpect(status().isOk())
                .andExpect(header().exists("Access-Control-Allow-Origin"))
                .andExpect(header().string("Access-Control-Allow-Origin", testOrigin));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:3000",             // Local development HTTP
            "https://localhost:3000",            // Local development HTTPS
            "http://192.168.0.18",               // Local network
            "https://192.168.0.18",              // Local network HTTPS
            "http://192.168.1.20",               // Local network different subnet
            "https://b2b6-89-103-185-10.ngrok-free.app",  // Ngrok tunnel
            "https://t1.domain.org",             // Cloudflare tunnel
    })
    void allowedOriginsTest(String testOrigin) throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .header("Origin", testOrigin))
                .andExpect(status().isOk())
                .andExpect(header().exists("Access-Control-Allow-Origin"))
                .andExpect(header().string("Access-Control-Allow-Origin", testOrigin));
    }
}
