package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaSparqlClient;
import com.dia.ismdtoolbackend.models.eli.FragmentResolutionModel;
import com.dia.ismdtoolbackend.utility.sparql.SparqlCircuitBreaker;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Caching wrapper around {@link EsbirkaSparqlClient#resolveFragment(String, String, String)}.
 * Extracted to a dedicated component so Spring's caching proxy intercepts the
 * call — {@code @Cacheable} on a method called from the same bean (e.g. inside
 * {@code EsbirkaServiceImpl}) would be bypassed.
 *
 * <p>Cache key is the canonical fragment IRI so legacy-host duplicates collapse
 * to a single cache entry. {@code versionIri}/{@code lawIri} are not part of
 * the key — they're derivable from {@code fragmentIri} and serve only as SPARQL
 * inputs.
 *
 * <p>A {@link SparqlCircuitBreaker} guards the underlying SPARQL call so a
 * sustained e-Sbírka outage fast-fails after a few failures rather than letting
 * every cache miss wait on the 10s SPARQL timeout.
 */
@Component
@RequiredArgsConstructor
public class EsbirkaFragmentResolutionCache {

    private final EsbirkaSparqlClient client;

    @Value("${esbirka.resolve.circuit-breaker.failure-threshold:5}")
    private int failureThreshold;

    @Value("${esbirka.resolve.circuit-breaker.cooldown-ms:30000}")
    private long cooldownMillis;

    private SparqlCircuitBreaker breaker;

    @PostConstruct
    void initBreaker() {
        this.breaker = new SparqlCircuitBreaker(
                EsbirkaSparqlClient.ESBIRKA_LABEL, failureThreshold, cooldownMillis);
    }

    @Cacheable(cacheNames = "esbirkaFragmentResolution", key = "#fragmentIri")
    public Optional<FragmentResolutionModel> fetch(String fragmentIri, String versionIri, String lawIri) {
        return breaker.call(() -> client.resolveFragment(fragmentIri, versionIri, lawIri));
    }
}
