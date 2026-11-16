package com.dia.ismdtoolbackend.config.cors;

import com.dia.ismdtoolbackend.config.DomainApplicationProfile;
import com.dia.ismdtoolbackend.publication.PublicationController;
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

        if (DomainApplicationProfile.isActive(env, DomainApplicationProfile.PRODUCTION)) {
            return productionCorsConfigurationSource();
        } else if (DomainApplicationProfile.isActive(env, DomainApplicationProfile.TEST)) {
            return testCorsConfigurationSource();
        } else {
            return localCorsConfigurationSource();
        }
    }

    @Bean
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
        return new CorsFilter(corsConfigurationSource);
    }

    private CorsConfigurationSource productionCorsConfigurationSource() {
        var config = new CorsConfigUtil().createProductionCorsConfiguration(productionAllowedOrigins());
        return withPublication(config);
    }

    /**
     * The two published open-data files, by exact path, are readable from any origin (NKOD checks CORS on distributions); every
     * other path keeps the profile's configuration. The first matching pattern wins, so publication is
     * registered first.
     */
    private static CorsConfigurationSource withPublication(CorsConfiguration config) {
        var source = new UrlBasedCorsConfigurationSource();
        var publicRead = new CorsConfigUtil().createPublicReadCorsConfiguration();
        for (String file : PublicationController.FILES) {
            source.registerCorsConfiguration(PublicationController.BASE_PATH + "/" + file, publicRead);
        }
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private List<String> productionAllowedOrigins() {
        return Arrays.stream(env.getProperty(ALLOWED_ORIGINS_PROPERTY, "").split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    private CorsConfigurationSource testCorsConfigurationSource() {
        return withPublication(new CorsConfigUtil().createTestCorsConfiguration());
    }

    private CorsConfigurationSource localCorsConfigurationSource() {
        return withPublication(new CorsConfigUtil().createLocalCorsConfiguration());
    }
}