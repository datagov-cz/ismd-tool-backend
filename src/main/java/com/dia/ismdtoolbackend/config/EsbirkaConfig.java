package com.dia.ismdtoolbackend.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "esbirka")
public class EsbirkaConfig {

    /** {@code sparql} or {@code rest}; selects the e-Sbírka transport. */
    private String provider = "sparql";
    private Sparql sparql = new Sparql();
    private Rest rest = new Rest();
    private Search search = new Search();

    @Data
    public static class Sparql {
        private String endpoint = "";
        private int timeout = 10000;
        private int maxConcurrentRequests = 8;
        private CircuitBreaker circuitBreaker = new CircuitBreaker();
    }

    @Data
    public static class Rest {
        private String baseUrl = "https://api.e-sbirka.gov.cz";
        @ToString.Exclude
        private String apiKey = "";
        private int timeout = 20000;
        private long requestDeadlineMs = 60000;
        private long minRequestSpacingMs = 40;
        private int maxInFlight = 4;
        private Retry retry = new Retry();
        private CircuitBreaker circuitBreaker = new CircuitBreaker();
    }

    @Data
    public static class Retry {
        private int maxAttempts = 4;
        private long initialBackoffMs = 100;
    }

    @Data
    public static class CircuitBreaker {
        private int failureThreshold = 5;
        private long cooldownMs = 30000;
    }

    @Data
    public static class Search {
        private int maxLimit = 50;
        private int defaultLimit = 20;
    }
}
