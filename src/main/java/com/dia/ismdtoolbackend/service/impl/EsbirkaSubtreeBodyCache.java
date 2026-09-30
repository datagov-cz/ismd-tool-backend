package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaFragmentHtml;
import com.dia.ismdtoolbackend.utility.sparql.SparqlCircuitBreaker;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-version map of fragment IRI → assembled subtree HTML, backing the {@code /resolve}
 * body fallback for structural fragments that carry no {@code obsah} of their own — the
 * document root and the containers (norma, poznamkypodcarou, postfix, …).
 *
 * <p>Keyed by version IRI rather than per fragment: assembling any one container needs the
 * whole version's rows anyway, so one fetch serves every container of that znění. Entries are
 * built eagerly for all fragments in the version, which costs one map per version and turns
 * subsequent container resolves into map hits.
 *
 * <p>Extracted to a dedicated component so Spring's caching proxy intercepts the call —
 * {@code @Cacheable} on a method called from within {@code EsbirkaServiceImpl} would be
 * bypassed. A {@link SparqlCircuitBreaker} guards the fetch so a sustained e-Sbírka outage
 * fast-fails instead of waiting on the SPARQL timeout at every miss.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsbirkaSubtreeBodyCache {

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

    /**
     * Assembled subtree HTML for every fragment of {@code versionIri}, keyed by fragment IRI.
     * Empty when the version has no fragments.
     */
    @Cacheable(cacheNames = "esbirkaVersionSubtreeBodies", key = "#versionIri")
    public Map<String, String> bodiesByFragmentIri(String versionIri) {
        List<FragmentModel> rows = breaker.call(() -> client.fetchVersionContent(versionIri));
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<FragmentDto> roots = EsbirkaServiceImpl.assembleTree(rows, versionIri);
        Map<String, String> out = new HashMap<>(rows.size());
        for (FragmentDto root : roots) {
            index(root, out);
        }
        log.debug("Assembled {} subtree bodies for version {}.", out.size(), versionIri);
        return Map.copyOf(out);
    }

    private static void index(FragmentDto node, Map<String, String> out) {
        if (node.getIri() != null) {
            out.put(node.getIri(), EsbirkaFragmentHtml.renderNode(node));
        }
        for (FragmentDto child : node.getChildren()) {
            index(child, out);
        }
    }
}