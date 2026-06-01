package com.dia.ismdtoolbackend.config.cors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

@Slf4j
public class CorsConfigUtil {

    private static final List<String> DEV_PATTERNS = List.of(
            // access from localhost
            "http://localhost:[*]",
            "https://localhost:[*]",

            // access from localhost - mobile device on same network
            "http://192.168.*.*",
            "https://192.168.*.*",

            // tunnel from localhost: https://ngrok.com
            "https://*.ngrok-free.app",

            // tunnel from localhost
            // via cloudflare https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/get-started/create-remote-tunnel/
            "https://*.domain.org",

            // Docker container/service names (aligned in docker-compose.yml)
            "http://ismd-validator-frontend:[*]",
            "http://ismd-validator-backend:[*]",
            
            // Docker host.docker.internal (for standalone Docker containers)
            "http://host.docker.internal:[*]",
            "https://host.docker.internal:[*]"
    );

    public CorsConfiguration createProductionCorsConfiguration() {
        var config = new CorsConfiguration();
        // TODO: change to relevant domain before production release
        config.setAllowedOrigins(List.of("https://www.domain.com"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        // TODO: switch to 1 day when this setting is verified
        // 1 day
        // config.setMaxAge(86400L);

        // 10 minutes
        config.setMaxAge(600L);

        return config;
    }

    public CorsConfiguration createTestCorsConfiguration() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("https://www.domain.org"));
        config.setAllowedOriginPatterns(DEV_PATTERNS);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        config.setMaxAge(3600L);

        return config;
    }

    public CorsConfiguration createLocalCorsConfiguration() {
        var config = new CorsConfiguration();
        
        // Get allowed origins from environment variable
        String corsAllowedOrigins = System.getenv("CORS_ALLOWED_ORIGINS");
        if (corsAllowedOrigins != null && !corsAllowedOrigins.isEmpty()) {
            // Add the specific allowed origin from environment variable
            log.info("CORS allowed origins set from environment: {}", corsAllowedOrigins);
            config.setAllowedOrigins(List.of(corsAllowedOrigins));
        }
        
        // Always include development patterns for local environment
        config.setAllowedOriginPatterns(DEV_PATTERNS);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        // 10 minutes
        config.setMaxAge(600L);

        return config;
    }
}
