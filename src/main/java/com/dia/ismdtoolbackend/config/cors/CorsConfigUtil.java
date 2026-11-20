package com.dia.ismdtoolbackend.config.cors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

@Slf4j
public class CorsConfigUtil {

    /** Origins a developer machine uses; allowed only by the local configuration. */
    private static final List<String> LOCAL_DEVELOPMENT_PATTERNS = List.of(
            // access from localhost
            "http://localhost:[*]",
            "https://localhost:[*]",

            // access from localhost - mobile device on same network
            "http://192.168.*.*",
            "https://192.168.*.*",

            // tunnel from localhost: https://ngrok.com
            "https://*.ngrok-free.app",

            // Docker container/service names (aligned in docker-compose.yml)
            "http://ismd-validator-frontend:[*]",
            "http://ismd-validator-backend:[*]",

            // Docker host.docker.internal (for standalone Docker containers)
            "http://host.docker.internal:[*]",
            "https://host.docker.internal:[*]"
    );

    /**
     * Deployed environments (production, test, dev): only {@code allowedOrigins}; no origin is allowed
     * while it is empty.
     */
    public CorsConfiguration createDeployedCorsConfiguration(List<String> allowedOrigins) {
        if (allowedOrigins.isEmpty()) {
            log.warn("No CORS origins configured (cors.allowed-origins); cross-origin requests are rejected");
        }
        var config = baseConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        return config;
    }

    /** Local development: {@code allowedOrigins} plus the developer-machine patterns. */
    public CorsConfiguration createLocalCorsConfiguration(List<String> allowedOrigins) {
        var config = baseConfiguration();
        if (!allowedOrigins.isEmpty()) {
            log.info("CORS allowed origins: {}", allowedOrigins);
            config.setAllowedOrigins(allowedOrigins);
        }
        config.setAllowedOriginPatterns(LOCAL_DEVELOPMENT_PATTERNS);
        return config;
    }

    private static CorsConfiguration baseConfiguration() {
        var config = new CorsConfiguration();
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        // 10 minutes
        config.setMaxAge(600L);
        return config;
    }
}
