package com.dia.ismdtoolbackend.config.cors;

import com.dia.ismdtoolbackend.config.DomainApplicationProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * Production, test and dev allow only {@code cors.allowed-origins} ({@code CORS_ALLOWED_ORIGINS});
 * local and junit add the developer-machine origins.
 */
@Configuration
@Slf4j
public class CorsConfig {

    private static final String ALLOWED_ORIGINS_PROPERTY = "cors.allowed-origins";

    private final Environment env;

    public CorsConfig(Environment env) {
        this.env = env;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        String activeProfile = DomainApplicationProfile.getProfile(env);
        log.info("Configuring CORS for profile: {}", activeProfile);

        CorsConfigUtil util = new CorsConfigUtil();
        CorsConfiguration config = isDeployed(activeProfile)
                ? util.createDeployedCorsConfiguration(allowedOrigins())
                : util.createLocalCorsConfiguration(allowedOrigins());
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
        return new CorsFilter(corsConfigurationSource);
    }

    private static boolean isDeployed(String profile) {
        return DomainApplicationProfile.PRODUCTION.equals(profile)
                || DomainApplicationProfile.TEST.equals(profile)
                || DomainApplicationProfile.DEV.equals(profile);
    }

    private List<String> allowedOrigins() {
        return Arrays.stream(env.getProperty(ALLOWED_ORIGINS_PROPERTY, "").split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }
}
