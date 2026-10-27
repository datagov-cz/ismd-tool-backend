package com.dia.ismdtoolbackend.config.cors;

import com.dia.exceptions.DomainException;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CorsConfig} per profile, through the {@link CorsFilter} it builds: which origins get
 * {@code Access-Control-Allow-Origin}, which are rejected with 403, and what a preflight answers.
 */
class CorsConfigTest {

    private static final String PATH = "/actuator/health";

    /** Local, LAN, tunnel and Docker origins the frontend uses during development. */
    private static final String[] DEV_ORIGINS = {
            "http://localhost:3000",
            "https://localhost:3000",
            "http://192.168.0.18",
            "https://192.168.0.18",
            "http://192.168.1.20",
            "https://b2b6-89-103-185-10.ngrok-free.app",
            "https://t1.domain.org",
            "http://ismd-validator-frontend:3000",
            "http://host.docker.internal:8080"
    };

    @Nested
    class Production {

        private final CorsFilter filter = filterFor(new MockEnvironment()
                .withProperty("cors.allowed-origins", "https://app.example.test, https://admin.example.test"),
                "production");

        @ParameterizedTest
        @ValueSource(strings = {"https://app.example.test", "https://admin.example.test"})
        void configuredOriginIsAllowed(String origin) throws Exception {
            MockHttpServletResponse response = get(filter, origin);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
            assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "https://www.twitter.com",
                "https://www.domain.com",
                "http://localhost:3000",
                "https://malicious-site.com",
                "https://192.168.1.1",
                "https://t1.domain.org",
                "http://app.example.test"
        })
        void otherOriginIsRejected(String origin) throws Exception {
            MockHttpServletResponse response = get(filter, origin);

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        }

        @Test
        void preflightAllowsPatchWithCredentialsForTenMinutes() throws Exception {
            MockHttpServletResponse response = preflight(filter, "https://app.example.test", "PATCH");

            assertPreflightAllowed(response, "https://app.example.test", "600");
        }

        @Test
        void noConfiguredOriginsAllowsNoOrigin() throws Exception {
            CorsFilter unconfigured = filterFor(new MockEnvironment(), "production");

            assertThat(get(unconfigured, "https://app.example.test").getStatus()).isEqualTo(403);
            assertThat(new CorsConfigUtil().createProductionCorsConfiguration(List.of())
                    .checkOrigin("https://app.example.test")).isNull();
        }
    }

    @Nested
    class TestEnvironment {

        private final CorsFilter filter = filterFor(new MockEnvironment(), "test");

        @Test
        void frontendDomainIsAllowed() throws Exception {
            MockHttpServletResponse response = get(filter, "https://www.domain.org");

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://www.domain.org");
        }

        /** The development pattern {@code https://*.domain.org} matches it too, so only the list shows it. */
        @Test
        void frontendDomainIsListedExplicitly() {
            assertThat(configurationFor(new MockEnvironment(), "test").getAllowedOrigins())
                    .containsExactly("https://www.domain.org");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://localhost:3000",
                "https://localhost:3000",
                "http://192.168.0.18",
                "https://b2b6-89-103-185-10.ngrok-free.app",
                "https://t1.domain.org"
        })
        void developmentOriginIsAllowed(String origin) throws Exception {
            assertThat(get(filter, origin).getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
        }

        @ParameterizedTest
        @ValueSource(strings = {"https://evil.com", "http://www.domain.org", "https://domain.org.evil.com"})
        void otherOriginIsRejected(String origin) throws Exception {
            MockHttpServletResponse response = get(filter, origin);

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        }

        /** The one-hour max age tells the test configuration apart from the local one. */
        @Test
        void preflightAllowsPatchWithCredentialsForOneHour() throws Exception {
            MockHttpServletResponse response = preflight(filter, "https://www.domain.org", "PATCH");

            assertPreflightAllowed(response, "https://www.domain.org", "3600");
        }
    }

    @Nested
    class Local {

        @ParameterizedTest
        @ValueSource(strings = {"local", "dev", "junit"})
        void everyDevelopmentOriginIsAllowed(String profile) throws Exception {
            CorsFilter filter = filterFor(new MockEnvironment(), profile);

            for (String origin : DEV_ORIGINS) {
                MockHttpServletResponse response = get(filter, origin);
                assertThat(response.getStatus()).as(origin).isEqualTo(200);
                assertThat(response.getHeader("Access-Control-Allow-Origin")).as(origin).isEqualTo(origin);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "https://evil.com",
                "http://localhost.evil.com",
                "http://10.0.0.5",
                "https://www.domain.com",
                "http://ngrok-free.app.evil.com"
        })
        void otherOriginIsRejected(String origin) throws Exception {
            MockHttpServletResponse response = get(filterFor(new MockEnvironment(), "local"), origin);

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        }

        @Test
        void preflightAllowsPatchWithCredentialsForTenMinutes() throws Exception {
            MockHttpServletResponse response =
                    preflight(filterFor(new MockEnvironment(), "local"), "http://localhost:3000", "PATCH");

            assertPreflightAllowed(response, "http://localhost:3000", "600");
        }

        @Test
        void requestWithoutOriginPassesUntouched() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filterFor(new MockEnvironment(), "local")
                    .doFilter(new MockHttpServletRequest("GET", PATH), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        }
    }

    @Test
    void productionWinsOverOtherActiveProfiles() {
        CorsConfiguration config = configurationFor(new MockEnvironment()
                .withProperty("cors.allowed-origins", "https://app.example.test"), "test", "production");

        assertThat(config.getAllowedOrigins()).containsExactly("https://app.example.test");
        assertThat(config.getAllowedOriginPatterns()).isNull();
    }

    @Test
    void noActiveProfileFailsFast() {
        CorsConfig corsConfig = new CorsConfig(new MockEnvironment());

        assertThatThrownBy(corsConfig::corsConfigurationSource).isInstanceOf(DomainException.class);
    }

    private static CorsFilter filterFor(MockEnvironment env, String... profiles) {
        env.setActiveProfiles(profiles);
        CorsConfig corsConfig = new CorsConfig(env);
        return corsConfig.corsFilter(corsConfig.corsConfigurationSource());
    }

    private static CorsConfiguration configurationFor(MockEnvironment env, String... profiles) {
        env.setActiveProfiles(profiles);
        CorsConfigurationSource source = new CorsConfig(env).corsConfigurationSource();
        return source.getCorsConfiguration(new MockHttpServletRequest("GET", PATH));
    }

    private static MockHttpServletResponse get(CorsFilter filter, String origin)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        request.addHeader("Origin", origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletResponse preflight(CorsFilter filter, String origin, String method)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", PATH);
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", method);
        request.addHeader("Access-Control-Request-Headers", "Authorization, Content-Type");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).as("a preflight is answered by the filter, not passed on").isNull();
        return response;
    }

    private static void assertPreflightAllowed(MockHttpServletResponse response, String origin, String maxAge) {
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
        assertThat(response.getHeader("Access-Control-Allow-Methods"))
                .contains("GET", "POST", "PUT", "DELETE", "PATCH");
        assertThat(response.getHeader("Access-Control-Allow-Headers"))
                .containsIgnoringCase("authorization").containsIgnoringCase("content-type");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(response.getHeader("Access-Control-Max-Age")).isEqualTo(maxAge);
    }
}
