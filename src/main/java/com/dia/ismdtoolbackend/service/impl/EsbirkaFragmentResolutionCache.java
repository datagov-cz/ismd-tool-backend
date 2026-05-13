package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaSparqlClient;
import com.dia.ismdtoolbackend.models.eli.FragmentResolutionModel;
import lombok.RequiredArgsConstructor;
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
 */
@Component
@RequiredArgsConstructor
public class EsbirkaFragmentResolutionCache {

    private final EsbirkaSparqlClient client;

    @Cacheable(cacheNames = "esbirkaFragmentResolution", key = "#fragmentIri")
    public Optional<FragmentResolutionModel> fetch(String fragmentIri, String versionIri, String lawIri) {
        return client.resolveFragment(fragmentIri, versionIri, lawIri);
    }
}
