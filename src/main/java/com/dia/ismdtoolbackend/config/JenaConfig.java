package com.dia.ismdtoolbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Semaphore;

@Configuration
public class JenaConfig {

    @Value("${jena.fuseki.url:http://localhost:3030/ismd-tool-dataset}")
    private String fusekiUrl;

    @Value("${jena.fuseki.connection-timeout:5000}")
    private int connectionTimeout;

    @Value("${jena.fuseki.max-concurrent-requests:10}")
    private int maxConcurrentRequests;

    @Value("${jena.fuseki.semaphore-timeout:30000}")
    private int semaphoreTimeout;

    @Bean
    public String fusekiEndpoint() {
        return fusekiUrl;
    }

    @Bean
    public HttpClient fusekiHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectionTimeout))
                .build();
    }

    @Bean
    public Semaphore fusekiSemaphore() {
        return new Semaphore(maxConcurrentRequests, true);
    }

    @Bean
    public int fusekiSemaphoreTimeout() {
        return semaphoreTimeout;
    }
}
