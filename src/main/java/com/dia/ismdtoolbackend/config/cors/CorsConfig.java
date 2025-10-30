package com.dia.ismdtoolbackend.config.cors;

import com.dia.ismdtoolbackend.config.DomainApplicationProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
@Slf4j
public class CorsConfig {

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
        } else if (DomainApplicationProfile.isActive(env, DomainApplicationProfile.STAGE)) {
            return stageCorsConfigurationSource();
        } else {
            return localCorsConfigurationSource();
        }
    }

    @Bean
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
        return new CorsFilter(corsConfigurationSource);
    }

    private CorsConfigurationSource productionCorsConfigurationSource() {
        var config = new CorsConfigUtil().createProductionCorsConfiguration();
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private CorsConfigurationSource stageCorsConfigurationSource() {
        var config = new CorsConfigUtil().createStageCorsConfiguration();
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private CorsConfigurationSource localCorsConfigurationSource() {
        var config = new CorsConfigUtil().createLocalCorsConfiguration();
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}