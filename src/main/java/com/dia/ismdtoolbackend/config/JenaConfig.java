package com.dia.ismdtoolbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JenaConfig {

    @Value("${jena.fuseki.url:http://localhost:3030/ismd-tool-dataset}")
    private String fusekiUrl;

    @Bean
    public String fusekiEndpoint() {
        return fusekiUrl;
    }
}