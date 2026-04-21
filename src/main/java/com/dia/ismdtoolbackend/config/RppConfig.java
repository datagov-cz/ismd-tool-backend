package com.dia.ismdtoolbackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rpp")
public class RppConfig {

    private Sparql sparql = new Sparql();
    private Cache cache = new Cache();
    private Search search = new Search();

    @Data
    public static class Sparql {
        private String endpoint = "";
        private int timeout = 10000;
    }

    @Data
    public static class Cache {
        private long ttlHours = 24;
    }

    @Data
    public static class Search {
        private int maxLimit = 50;
        private int defaultLimit = 20;
    }
}