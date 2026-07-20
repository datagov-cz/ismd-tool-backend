package com.dia.ismdtoolbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared {@link HttpClient} for the external HTTP SPARQL endpoints (NKD, RPP, e-Sbírka).
 *
 * <p>A single {@code HttpClient} is thread-safe and maintains a per-destination connection
 * pool, so one instance serving all three endpoints keeps independent keep-alive pools per
 * host. Without this, {@code QueryExecutionHTTPBuilder.service(url)} would build a fresh
 * client per call and every SPARQL round-trip would pay a new TCP+TLS handshake — costly on
 * the deviation hot path, which fires one NKD CONSTRUCT per published concept.
 *
 * <p>Internal Fuseki already pools via {@link JenaConfig#fusekiHttpClient()}; this is the
 * external-endpoint counterpart. Per-query timeouts stay on each {@code QueryExecution} — this
 * bean only governs the connect timeout and connection reuse.
 */
@Configuration
public class ExternalSparqlConfig {

    @Bean
    public HttpClient externalSparqlHttpClient(
            @Value("${sparql.http.connect-timeout-ms:5000}") int connectTimeoutMs) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }
}