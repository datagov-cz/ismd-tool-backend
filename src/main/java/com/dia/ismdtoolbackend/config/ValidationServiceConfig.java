package com.dia.ismdtoolbackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration for the external ISMD validator integration. The validator is deployed
 * separately (its own repo and lifecycle), so the tool treats it as untrusted-availability
 * infrastructure — explicit timeouts and a circuit breaker keep a slow or dead validator
 * from blocking the tool, mirroring the SPARQL-client robustness layer.
 *
 * <p>Bound to {@code validation.service.*}. Distinct from {@code ValidationConfig}
 * ({@code validation.rules.*}), which carries an unrelated download flag.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "validation.service")
public class ValidationServiceConfig {

    /** Base URL of the validator service (includes its context path, e.g. {@code .../validujeme}). */
    private String url = "";

    /** TCP connect timeout for validator calls. */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Read timeout for validator calls. Deliberately above the validator's own 10s SHACL cap
     * so a legitimately slow-but-working validation completes; only a true hang trips it.
     */
    private Duration readTimeout = Duration.ofSeconds(15);

    private Breaker breaker = new Breaker();

    @Data
    public static class Breaker {
        /** Consecutive unavailable-failures before the breaker opens. */
        private int failureThreshold = 5;
        /** How long the breaker stays open before letting a trial call through. */
        private Duration cooldown = Duration.ofSeconds(30);
    }
}
