package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;

/**
 * Resolves ontology {@code skos:prefLabel} language maps, keyed by graph IRI.
 *
 * <p>Ontology names live only in RDF — Postgres holds the slug and graph name but no label — so any
 * PG-projected payload that wants to show a name has to reach Fuseki for it. This does that in ONE
 * batched {@code VALUES} CONSTRUCT for a whole page (see
 * {@link JenaTDB2Repository#fetchMetadataProperties(List)}), never one call per row.
 *
 * <p>Fails soft: a Fuseki outage yields an empty map and the caller simply omits the label, since a
 * missing name must not fail a list that is otherwise fully answerable from Postgres.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OntologyLabelLookup {

    private final JenaTDB2Repository jenaTDB2Repository;

    /**
     * Language→label maps for the given graph IRIs, keyed by graph IRI. Graphs with no
     * {@code skos:prefLabel} are absent from the result rather than mapped to an empty map, so a
     * caller can distinguish "no label" from "empty label" with a plain {@code get}.
     */
    public Map<String, Map<String, String>> labelsByGraph(Collection<String> graphNames) {
        if (graphNames == null || graphNames.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = graphNames.stream()
                .filter(g -> g != null && !g.isEmpty())
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }

        Model metadata;
        try {
            metadata = jenaTDB2Repository.fetchMetadataProperties(distinct);
        } catch (RuntimeException e) {
            log.warn("Could not fetch ontology labels for {} graph(s); omitting them", distinct.size(), e);
            return Map.of();
        }
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }

        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String graphName : distinct) {
            Resource ontology = metadata.getResource(graphName);
            if (ontology == null) {
                continue;
            }
            Map<String, String> labels = extractMultilingualValue(ontology);
            if (!labels.isEmpty()) {
                out.put(graphName, labels);
            }
        }
        return out;
    }

    /** Convenience for a single graph; null when the ontology carries no label. */
    public Map<String, String> labelFor(String graphName) {
        if (graphName == null || graphName.isEmpty()) {
            return null;
        }
        Map<String, Map<String, String>> labels = labelsByGraph(List.of(graphName));
        return labels == null ? null : labels.get(graphName);
    }

    /**
     * Language→value map for the resource's {@code skos:prefLabel}s. An untagged literal is keyed
     * under {@link com.dia.constants.ExportConstants.Common#DEFAULT_LANG}, and the first value per
     * language wins — matching {@code OntologyServiceImpl.extractMultilingualValue}.
     */
    private Map<String, String> extractMultilingualValue(Resource resource) {
        Map<String, String> valuesByLang = new LinkedHashMap<>();
        StmtIterator iter = resource.listProperties(SKOS.prefLabel);
        try {
            while (iter.hasNext()) {
                Statement stmt = iter.next();
                RDFNode object = stmt.getObject();
                if (!object.isLiteral()) {
                    continue;
                }
                Literal literal = object.asLiteral();
                String value = literal.getString();
                if (value == null || value.trim().isEmpty()) {
                    continue;
                }
                String lang = literal.getLanguage();
                valuesByLang.putIfAbsent((lang != null && !lang.isEmpty()) ? lang : DEFAULT_LANG, value);
            }
        } finally {
            iter.close();
        }
        return valuesByLang;
    }
}