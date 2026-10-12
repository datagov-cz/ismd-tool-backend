package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.ismdtoolbackend.utility.sparql.FusekiSparqlExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.VLASTNOST;
import static com.dia.constants.VocabularyConstants.VZTAH;

/** Concept-level reads against Fuseki. Reached through {@link JenaTDB2Repository}. */
@Slf4j
final class FusekiConceptQueries {

    private final FusekiSparqlExecutor executor;

    FusekiConceptQueries(FusekiSparqlExecutor executor) {
        this.executor = executor;
    }

    /**
     * Returns the triples describing every property/relationship across ALL local
     * graphs whose {@code rdfs:domain} <em>or</em> {@code rdfs:range} is {@code conceptIri}
     * — i.e. the members that point <em>at</em> this concept. Merged into the concept's
     * own graph by the detail pipeline so the class-detail read
     * ({@link com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor#extractConceptPropertiesFromModel})
     * sees members that live in a different vocabulary graph than the class.
     *
     * <p>The class-detail read fetches only the class's own named graph, so a
     * cross-vocabulary {@code member rdfs:domain class} (or {@code rdfs:range}) edge
     * (the member living in graph A, the class in graph B) was invisible from the
     * class side even though the member's own detail showed it. This closes that
     * asymmetry. Range is matched too: a vztah whose range is this class would
     * otherwise never surface on the class detail.
     *
     * <p>Returns exactly the triples the extractor reads off each member resource:
     * {@code rdf:type}, {@code skos:prefLabel}, {@code rdfs:domain}, {@code rdfs:range}.
     * The member's actual domain/range edges are constructed (not the match edge), so a
     * range-matched member still carries its own domain, and vice versa.
     */
    public Model fetchExternalDomainMembers(String conceptIri) {
        if (!SparqlIriValidator.isSafeHttpIri(conceptIri)) {
            log.warn("Skipping fetchExternalDomainMembers for unsafe concept IRI");
            return ModelFactory.createDefaultModel();
        }
        return executor.execute(
                "fetching cross-graph domain/range members for " + conceptIri,
                "Failed to fetch cross-graph domain/range members",
                conn -> {
                    ParameterizedSparqlString pss = new ParameterizedSparqlString();
                    pss.append("PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> ");
                    pss.append("PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> ");
                    pss.append("PREFIX skos: <http://www.w3.org/2004/02/skos/core#> ");
                    pss.append("CONSTRUCT { ");
                    pss.append("  ?member rdf:type ?type . ");
                    pss.append("  ?member rdfs:domain ?domain . ");
                    pss.append("  ?member skos:prefLabel ?label . ");
                    pss.append("  ?member rdfs:range ?range . ");
                    pss.append("} WHERE { GRAPH ?g { ");
                    pss.append("  { ?member rdfs:domain ?concept } UNION { ?member rdfs:range ?concept } ");
                    pss.append("  ?member rdf:type ?type . ");
                    pss.append("  OPTIONAL { ?member skos:prefLabel ?label } ");
                    pss.append("  OPTIONAL { ?member rdfs:domain ?domain } ");
                    pss.append("  OPTIONAL { ?member rdfs:range ?range } ");
                    pss.append("} }");
                    pss.setIri("concept", conceptIri);
                    try (QueryExecution qExec = conn.query(pss.asQuery())) {
                        Model result = qExec.execConstruct();
                        log.debug("Fetched {} cross-graph domain/range-member triples for {}",
                                result.size(), conceptIri);
                        return result;
                    }
                });
    }

    /**
     * Per-concept count of properties/relationships that point at each given concept from a
     * graph OTHER than {@code ownGraphName}. One batched query for the whole concept list.
     *
     * <p>Ontology detail lists only members from its own graph
     * ({@code OntologyDetailExtractor.MemberIndex} is built from that graph's concept list
     * alone), while concept detail additionally merges cross-graph members via
     * {@link #fetchExternalDomainMembers}. These counts are what the two surfaces differ by,
     * so ontology detail can state how many members it is not showing.
     *
     * <p>Attachment matches the extractor exactly, per kind: a vlastnost counts for its
     * {@code rdfs:domain} only, a vztah for {@code rdfs:domain} OR {@code rdfs:range}
     * ({@code COUNT(DISTINCT ?member)} so a vztah pointing both at the same concept counts once).
     *
     * <p>Concepts with no foreign members are absent from the returned map.
     */
    public Map<String, ForeignMemberCount> countExternalDomainMembers(String ownGraphName,
                                                                     Collection<String> conceptIris) {
        if (conceptIris == null || conceptIris.isEmpty() || !SparqlIriValidator.isSafeHttpIri(ownGraphName)) {
            return Map.of();
        }

        // Validate before interpolating into VALUES: raw concatenation would let a crafted
        // stored IRI escape <...> and inject SPARQL. Invalid entries are dropped, not sanitized.
        List<String> safeConceptIris = conceptIris.stream()
                .filter(SparqlIriValidator::isSafeHttpIri)
                .distinct()
                .toList();
        if (safeConceptIris.isEmpty()) {
            return Map.of();
        }

        return executor.execute(
                "counting cross-graph members for " + safeConceptIris.size() + " concepts",
                "Failed to count cross-graph domain/range members",
                conn -> {
                    ParameterizedSparqlString pss = new ParameterizedSparqlString();
                    pss.append("PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> ");
                    pss.append("PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> ");
                    pss.append("SELECT ?concept ?kind (COUNT(DISTINCT ?member) AS ?n) WHERE { ");
                    pss.append("VALUES ?concept { ");
                    for (String iri : safeConceptIris) {
                        pss.appendIri(iri);
                        pss.append(" ");
                    }
                    pss.append("} GRAPH ?g { ");
                    pss.append("  { ?member rdfs:domain ?concept . ?member rdf:type ?kind . ");
                    pss.append("    FILTER(?kind = ?vlastnost) } ");
                    pss.append("  UNION ");
                    pss.append("  { { ?member rdfs:domain ?concept } UNION { ?member rdfs:range ?concept } ");
                    pss.append("    ?member rdf:type ?kind . FILTER(?kind = ?vztah) } ");
                    pss.append("} FILTER(?g != ?ownGraph) ");
                    pss.append("} GROUP BY ?concept ?kind");
                    pss.setIri("ownGraph", ownGraphName);
                    pss.setIri("vlastnost", OFN_NAMESPACE + VLASTNOST);
                    pss.setIri("vztah", OFN_NAMESPACE + VZTAH);

                    Map<String, Integer> properties = new HashMap<>();
                    Map<String, Integer> relationships = new HashMap<>();
                    try (QueryExecution qExec = conn.query(pss.asQuery())) {
                        ResultSet rs = qExec.execSelect();
                        while (rs.hasNext()) {
                            QuerySolution row = rs.next();
                            Resource concept = row.getResource("concept");
                            Resource kind = row.getResource("kind");
                            if (concept == null || !concept.isURIResource() || kind == null) {
                                continue;
                            }
                            int n = row.getLiteral("n").getInt();
                            if (n <= 0) {
                                continue;
                            }
                            if ((OFN_NAMESPACE + VLASTNOST).equals(kind.getURI())) {
                                properties.merge(concept.getURI(), n, Integer::sum);
                            } else if ((OFN_NAMESPACE + VZTAH).equals(kind.getURI())) {
                                relationships.merge(concept.getURI(), n, Integer::sum);
                            }
                        }
                    }

                    Map<String, ForeignMemberCount> counts = new HashMap<>();
                    Set<String> touched = new HashSet<>(properties.keySet());
                    touched.addAll(relationships.keySet());
                    for (String conceptIri : touched) {
                        counts.put(conceptIri, new ForeignMemberCount(
                                properties.get(conceptIri), relationships.get(conceptIri)));
                    }
                    log.debug("Counted cross-graph members for {} of {} concept(s) outside graph {}",
                            counts.size(), safeConceptIris.size(), ownGraphName);
                    return counts;
                });
    }

    public List<String> findRelatedConceptUris(String conceptUri, String graphName) {
        return executor.execute(
                "finding related concepts for " + conceptUri + " in graph " + graphName,
                "Failed to find related concepts",
                conn -> {
                    ParameterizedSparqlString pss = getParameterizedSparqlString(conceptUri, graphName);
                    List<String> relatedUris = new ArrayList<>();
                    try (QueryExecution qExec = conn.query(pss.toString())) {
                        ResultSet results = qExec.execSelect();
                        while (results.hasNext()) {
                            QuerySolution solution = results.next();
                            Resource related = solution.getResource("related");
                            if (related != null && related.isURIResource()) {
                                relatedUris.add(related.getURI());
                            }
                        }
                    }
                    log.debug("Found {} related concepts for '{}' in graph '{}'",
                            relatedUris.size(), conceptUri, graphName);
                    return relatedUris;
                });
    }

    private static ParameterizedSparqlString getParameterizedSparqlString(String conceptUri, String graphName) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText(
                "SELECT ?related WHERE { " +
                "  GRAPH ?g { " +
                "    { ?related <http://www.w3.org/2000/01/rdf-schema#domain> ?concept } " +
                "    UNION " +
                "    { ?related <http://www.w3.org/2000/01/rdf-schema#range> ?concept } " +
                "  } " +
                "}"
        );
        pss.setIri("g", graphName);
        pss.setIri("concept", conceptUri);
        return pss;
    }

    /**
     * Fetches labels and descriptions for a batch of concept IRIs from Fuseki.
     * Returns a Model containing skos:prefLabel, skos:altLabel, dcterms:description,
     * and skos:definition triples.
     */
    public Model fetchConceptLabels(List<String> conceptIris) {
        if (conceptIris == null || conceptIris.isEmpty()) {
            return ModelFactory.createDefaultModel();
        }

        List<String> safeConceptIris = conceptIris.stream()
                .filter(SparqlIriValidator::isSafeHttpIri)
                .toList();
        if (safeConceptIris.size() != conceptIris.size()) {
            log.warn("Dropped {} invalid concept IRI(s) from fetchConceptLabels",
                    conceptIris.size() - safeConceptIris.size());
        }
        if (safeConceptIris.isEmpty()) {
            return ModelFactory.createDefaultModel();
        }

        return executor.execute(
                "fetching concept labels for " + safeConceptIris.size() + " IRIs",
                "Failed to fetch concept labels from Fuseki",
                conn -> {
                    ParameterizedSparqlString pss = new ParameterizedSparqlString();
                    pss.append("PREFIX skos: <http://www.w3.org/2004/02/skos/core#> ");
                    pss.append("PREFIX dcterms: <http://purl.org/dc/terms/> ");
                    pss.append("CONSTRUCT { ");
                    pss.append("  ?concept skos:prefLabel ?prefLabel . ");
                    pss.append("  ?concept skos:altLabel ?altLabel . ");
                    pss.append("  ?concept dcterms:description ?desc . ");
                    pss.append("  ?concept skos:definition ?def . ");
                    pss.append("} WHERE { VALUES ?concept { ");
                    for (String iri : safeConceptIris) {
                        pss.appendIri(iri);
                        pss.append(" ");
                    }
                    pss.append("} GRAPH ?g { ");
                    pss.append("  OPTIONAL { ?concept skos:prefLabel ?prefLabel } ");
                    pss.append("  OPTIONAL { ?concept skos:altLabel ?altLabel } ");
                    pss.append("  OPTIONAL { ?concept dcterms:description ?desc } ");
                    pss.append("  OPTIONAL { ?concept skos:definition ?def } ");
                    pss.append("} }");
                    try (QueryExecution qExec = conn.query(pss.asQuery())) {
                        Model result = qExec.execConstruct();
                        log.debug("Fetched concept labels for {} IRIs, result has {} statements",
                                safeConceptIris.size(), result.size());
                        return result;
                    }
                });
    }

    /**
     * The hierarchy links of a single concept: {@code rdfs:subClassOf} / {@code skos:broader} and
     * {@code skos:exactMatch}, as raw target IRIs. Deliberately narrow — {@link #fetchConceptResolutions}
     * already carries label, slug, domain and range, but not these, and fetching them was otherwise only
     * possible by pulling the whole named graph.
     *
     * <p>Returns targets, not resolved concepts: the caller feeds them straight back into
     * {@code ReferencedConceptResolutionEngine.resolveAll}, whose per-IRI cache usually already holds
     * them. Scoped to the concept's own graph so a stray cross-graph triple cannot inject a link.
     *
     * @return {@code subClassOf} and {@code exactMatch} target IRIs; empty lists when the concept has none
     */
    public ConceptHierarchyLinks fetchConceptHierarchy(String conceptIri, String graphName) {
        if (conceptIri == null || graphName == null || !SparqlIriValidator.isSafeHttpIri(conceptIri)) {
            return ConceptHierarchyLinks.empty();
        }

        return executor.execute(
                "fetching hierarchy links for " + conceptIri,
                "Failed to fetch concept hierarchy from Fuseki",
                conn -> {
                    ParameterizedSparqlString pss = new ParameterizedSparqlString();
                    pss.append("PREFIX skos: <http://www.w3.org/2004/02/skos/core#> ");
                    pss.append("PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> ");
                    pss.append("SELECT ?broader ?exact WHERE { GRAPH ");
                    pss.appendIri(graphName);
                    pss.append(" { ");
                    pss.append("  OPTIONAL { ");
                    pss.appendIri(conceptIri);
                    pss.append("    rdfs:subClassOf ?broader . FILTER(isIRI(?broader)) } ");
                    pss.append("  OPTIONAL { ");
                    pss.appendIri(conceptIri);
                    pss.append("    skos:broader ?broader . FILTER(isIRI(?broader)) } ");
                    pss.append("  OPTIONAL { ");
                    pss.appendIri(conceptIri);
                    pss.append("    skos:exactMatch ?exact . FILTER(isIRI(?exact)) } ");
                    pss.append("} }");

                    List<String> broader = new ArrayList<>();
                    List<String> exact = new ArrayList<>();
                    try (QueryExecution qExec = conn.query(pss.asQuery())) {
                        ResultSet rs = qExec.execSelect();
                        while (rs.hasNext()) {
                            QuerySolution row = rs.next();
                            collectIri(broader, row, "broader");
                            collectIri(exact, row, "exact");
                        }
                    }
                    return new ConceptHierarchyLinks(List.copyOf(broader), List.copyOf(exact));
                });
    }

    /** Append one solution's URI binding, skipping absent bindings and duplicates. */
    private static void collectIri(List<String> target, QuerySolution row, String var) {
        RDFNode node = row.get(var);
        if (node != null && node.isURIResource() && !target.contains(node.asResource().getURI())) {
            target.add(node.asResource().getURI());
        }
    }

    /**
     * Batched ISMD concept-reference resolver. For each input IRI present in the
     * local store, returns its {@code skos:inScheme} (ontology IRI) and the
     * scheme's multilingual {@code dcterms:description}. IRIs not in any local
     * graph are simply absent from the returned map — the caller falls back to
     * NKD (or to the plain IRI on the FE).
     *
     * <p>One CONSTRUCT for the whole batch = one Fuseki semaphore permit. With
     * only 10 permits total, per-IRI parallelism would serialize and quickly
     * saturate the pool; batching keeps the worst-case path cheap.
     *
     * <p>Prefix-filtered: only matches the scheme whose IRI is a string prefix
     * of the concept IRI. Local graphs sometimes contain stray
     * {@code skos:inScheme} triples that point a foreign concept at the wrong
     * scheme — without this filter the union-graph query returns those
     * non-deterministically.
     */
    public Map<String, ResolvedConceptDto> fetchConceptResolutions(List<String> conceptIris) {
        if (conceptIris == null || conceptIris.isEmpty()) {
            return Map.of();
        }

        List<String> safeConceptIris = conceptIris.stream()
                .filter(SparqlIriValidator::isSafeHttpIri)
                .toList();
        if (safeConceptIris.size() != conceptIris.size()) {
            log.warn("Dropped {} invalid concept IRI(s) from fetchConceptResolutions",
                    conceptIris.size() - safeConceptIris.size());
        }
        if (safeConceptIris.isEmpty()) {
            return Map.of();
        }

        return executor.execute(
                "fetching concept resolutions for " + safeConceptIris.size() + " IRIs",
                "Failed to fetch concept resolutions from Fuseki",
                conn -> {
                    ParameterizedSparqlString pss = new ParameterizedSparqlString();
                    pss.append("PREFIX skos: <http://www.w3.org/2004/02/skos/core#> ");
                    pss.append("PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> ");
                    pss.append("PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> ");
                    pss.append("CONSTRUCT { ");
                    pss.append("  ?concept skos:inScheme ?scheme . ");
                    pss.append("  ?concept skos:prefLabel ?conceptLabel . ");
                    pss.append("  ?concept rdf:type ?type . ");
                    pss.append("  ?concept rdfs:domain ?domain . ");
                    pss.append("  ?concept rdfs:range ?range . ");
                    pss.append("  ?scheme skos:prefLabel ?schemeLabel . ");
                    pss.append("} WHERE { VALUES ?concept { ");
                    for (String iri : safeConceptIris) {
                        pss.appendIri(iri);
                        pss.append(" ");
                    }
                    pss.append("} GRAPH ?g { ");
                    pss.append(JenaTDB2Repository.OWNED_CONCEPT_PATTERN);
                    pss.append("  OPTIONAL { ?concept skos:prefLabel ?conceptLabel . } ");
                    pss.append("  OPTIONAL { ?concept rdf:type ?type . } ");
                    pss.append("  OPTIONAL { ?concept rdfs:domain ?domain . } ");
                    pss.append("  OPTIONAL { ?concept rdfs:range ?range . } ");
                    pss.append("  OPTIONAL { ?scheme skos:prefLabel ?schemeLabel . } ");
                    pss.append("} }");
                    try (QueryExecution qExec = conn.query(pss.asQuery())) {
                        Model result = qExec.execConstruct();
                        Map<String, ResolvedConceptDto> resolutions =
                                ConceptResolutionProjection.projectResolutions(result, SearchSource.ISMD);
                        log.debug("Resolved {} of {} requested concept IRI(s) against ISMD",
                                resolutions.size(), safeConceptIris.size());
                        return resolutions;
                    }
                });
    }
}
