package com.dia.ismdtoolbackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "nkd")
public class NkdConfig {

    private Sparql sparql = new Sparql();
    private Snapshot snapshot = new Snapshot();

    @Data
    public static class Sparql {
        private String endpoint = "";
        private int timeout = 10000;
        private int maxConcurrentRequests = 4;
    }

    @Data
    public static class Snapshot {
        /**
         * How long a snapshot's cached deviation is considered fresh. A snapshot whose
         * {@code lastCheckedAt} is older (or null) is treated as cold on ontology-detail read:
         * surfaced as {@code PENDING} and re-evaluated by the async warmer.
         */
        private Duration deviationTtl = Duration.ofHours(24);
    }
}