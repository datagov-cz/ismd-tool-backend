package com.dia.ismdtoolbackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "nkd")
public class NkdConfig {

    private Sparql sparql = new Sparql();

    @Data
    public static class Sparql {
        private String endpoint = "";
        private int timeout = 10000;
        private int maxConcurrentRequests = 4;
    }
}