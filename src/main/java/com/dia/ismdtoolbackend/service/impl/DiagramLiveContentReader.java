package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.exception.DiagramReadbackFailedException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.diagram.BackingResolver;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the live concept content a diagram renders, from Fuseki. Called with no transaction open. */
@Slf4j
@Component
@RequiredArgsConstructor
class DiagramLiveContentReader {

    private final JenaTDB2Repository jenaTDB2Repository;
    private final OntologyDetailExtractor detailExtractor;

    /** Live content for an already-committed write; a Fuseki failure here is a readback, not a rollback. */
    BackingResolver readbackConcepts(DiagramSnapshot snapshot) {
        try {
            return liveContent(snapshot);
        } catch (RuntimeException e) {
            throw new DiagramReadbackFailedException(snapshot.version(), e);
        }
    }

    /**
     * Merges content for foreign nodes — concepts referenced from other ontologies — into the own-graph
     * map so they render with a label instead of a bare IRI. One fetch per distinct foreign graph;
     * own-graph entries win. The own graph fails closed, since without it there is no diagram to render; a
     * foreign graph that fails to load leaves the rest of the canvas usable and marks only its own nodes
     * unavailable.
     */
    BackingResolver liveContent(DiagramSnapshot snapshot) {
        Map<String, ConceptDetailModel> own = liveConcepts(snapshot.graphName());
        if (snapshot.foreignGraphs().isEmpty()) {
            return new BackingResolver(own, Set.of());
        }
        Map<String, List<String>> irisByGraph = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : snapshot.foreignGraphs().entrySet()) {
            irisByGraph.computeIfAbsent(e.getValue(), g -> new ArrayList<>()).add(e.getKey());
        }

        Map<String, ConceptDetailModel> merged = new HashMap<>(own);
        Set<String> unavailable = new HashSet<>();
        for (Map.Entry<String, List<String>> e : irisByGraph.entrySet()) {
            Map<String, ConceptDetailModel> foreign;
            try {
                foreign = liveConcepts(e.getKey());
            } catch (RuntimeException ex) {
                log.warn("Foreign graph {} could not be read for diagram {}; its {} node(s) render as "
                        + "unavailable, not deleted", e.getKey(), snapshot.diagramId(), e.getValue().size(), ex);
                unavailable.addAll(e.getValue());
                continue;
            }
            for (String iri : e.getValue()) {
                ConceptDetailModel detail = foreign.get(iri);
                if (detail != null) {
                    merged.putIfAbsent(iri, detail);
                }
            }
        }
        return new BackingResolver(merged, unavailable);
    }

    /** Live concept detail by IRI; empty when the graph has no concepts yet. */
    private Map<String, ConceptDetailModel> liveConcepts(String graphName) {
        Model raw = jenaTDB2Repository.fetchGraph(graphName);
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Model processed = detailExtractor.applyOFNTransformations(raw);
        OntologyDetailModel detail = detailExtractor.extractOntologyDetail(processed);
        Map<String, ConceptDetailModel> byIri = new HashMap<>();
        if (detail.getConcepts() != null) {
            for (ConceptDetailModel c : detail.getConcepts()) {
                byIri.put(c.getIri(), c);
            }
        }
        return byIri;
    }
}
