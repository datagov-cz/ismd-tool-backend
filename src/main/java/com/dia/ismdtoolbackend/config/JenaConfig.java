package com.dia.ismdtoolbackend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Semaphore;

@Slf4j
@Configuration
public class JenaConfig {

    @Value("${jena.fuseki.url:http://localhost:3030/ismd-tool-dataset}")
    private String fusekiUrl;

    @Value("${jena.fuseki.connection-timeout:5000}")
    private int connectionTimeout;

    /** Basic-auth user for Fuseki. Empty (the default) sends no credentials, as local compose expects. */
    @Value("${jena.fuseki.username:}")
    private String username;

    @Value("${jena.fuseki.password:}")
    private String password;

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
        return buildFusekiHttpClient(connectionTimeout, username, password);
    }

    /**
     * Credentials are answered to Fuseki's 401 challenge rather than sent up front, so the same client
     * works against an open Fuseki too. That lets credentials be configured before Fuseki enforces them.
     * Only this client carries them: NKD, RPP and e-Sbírka build their own.
     */
    static HttpClient buildFusekiHttpClient(int connectionTimeoutMs, String username, String password) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectionTimeoutMs));
        boolean hasUsername = StringUtils.hasText(username);
        boolean hasPassword = StringUtils.hasText(password);
        if (hasUsername != hasPassword) {
            throw new IllegalStateException(
                    "jena.fuseki.username and jena.fuseki.password must be set together, or both left empty");
        }
        if (hasUsername) {
            builder.authenticator(basicAuthenticator(username, password));
            log.info("Fuseki basic authentication enabled for user '{}'", username);
        }
        return builder.build();
    }

    private static Authenticator basicAuthenticator(String username, String password) {
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.SERVER) {
                    return null;
                }
                return new PasswordAuthentication(username, password.toCharArray());
            }
        };
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
