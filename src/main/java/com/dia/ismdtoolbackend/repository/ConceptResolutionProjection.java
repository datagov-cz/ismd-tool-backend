package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.VZTAH;

/** Projects a concept-resolution CONSTRUCT result into {@link ResolvedConceptDto}s. */
public final class ConceptResolutionProjection {

    static final String OWL_OBJECT_PROPERTY = "http://www.w3.org/2002/07/owl#ObjectProperty";

    private ConceptResolutionProjection() {
    }

    /**
     * Display-mode projection: like {@link #projectResolutions(Model, SearchSource)} but driven by the
     * requested IRIs rather than by {@code skos:inScheme}, so a concept NKD publishes without a scheme
     * still yields a DTO carrying its label. {@code ontologyIri}/{@code ontologyName} stay null for those.
     *
     * <p>Only for IRIs the client named explicitly — see
     * {@code NKDSPARQLConstructQuery.buildDisplayResolutionConstructQuery}.
     */
    public static Map<String, ResolvedConceptDto> projectDisplayResolutions(
            Model model, SearchSource source, List<String> requestedIris) {
        if (model == null || model.isEmpty() || requestedIris == null) {
            return Map.of();
        }
        Property inScheme = model.createProperty("http://www.w3.org/2004/02/skos/core#inScheme");
        Property prefLabel = model.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel");
        Property rdfType = model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        Property rdfsDomain = model.createProperty("http://www.w3.org/2000/01/rdf-schema#domain");
        Property rdfsRange = model.createProperty("http://www.w3.org/2000/01/rdf-schema#range");
        Set<String> relationshipTypeIris = Set.of(OFN_NAMESPACE + VZTAH, OWL_OBJECT_PROPERTY);

        Map<String, ResolvedConceptDto> out = new HashMap<>();
        for (String conceptIri : requestedIris) {
            Resource concept = model.getResource(conceptIri);
            // No type triple means the CONSTRUCT matched nothing for this IRI — absent, not unresolved.
            if (!concept.hasProperty(rdfType)) {
                continue;
            }
            Map<String, String> conceptLabels = collectMultilingual(concept, prefLabel);

            // Any one of several competing schemes; presentational only, never an ownership claim.
            Statement schemeStmt = concept.getProperty(inScheme);
            Resource scheme = schemeStmt != null && schemeStmt.getObject().isURIResource()
                    ? schemeStmt.getObject().asResource()
                    : null;
            Map<String, String> schemeLabels = scheme != null
                    ? collectMultilingual(scheme, prefLabel)
                    : Map.of();

            ResolvedConceptDto domainStub = null;
            ResolvedConceptDto rangeStub = null;
            if (hasAnyType(concept, rdfType, relationshipTypeIris)) {
                domainStub = resourceStub(concept, rdfsDomain);
                rangeStub = resourceStub(concept, rdfsRange);
            }

            out.put(conceptIri, ResolvedConceptDto.builder()
                    .iri(conceptIri)
                    .conceptName(conceptLabels.isEmpty() ? null : conceptLabels)
                    .ontologyIri(scheme != null ? scheme.getURI() : null)
                    .ontologyName(schemeLabels.isEmpty() ? null : schemeLabels)
                    .source(source)
                    .resolvedDomain(domainStub)
                    .resolvedRange(rangeStub)
                    .build());
        }
        return out;
    }

    /**
     * Iterates the result Model once and projects rows into a map keyed by concept
     * IRI. Same shape used by both ISMD ({@link JenaTDB2Repository#fetchConceptResolutions}) and the
     * NKD client — co-located here as a static helper so the two callers stay in
     * sync on parsing rules.
     */
    public static Map<String, ResolvedConceptDto> projectResolutions(Model model, SearchSource source) {
        if (model == null || model.isEmpty()) {
            return Map.of();
        }
        Property inScheme = model.createProperty("http://www.w3.org/2004/02/skos/core#inScheme");
        Property prefLabel = model.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel");
        Property rdfType = model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        Property rdfsDomain = model.createProperty("http://www.w3.org/2000/01/rdf-schema#domain");
        Property rdfsRange = model.createProperty("http://www.w3.org/2000/01/rdf-schema#range");
        // A relationship is typed by the OFN role IRI (…/vztah) in ISMD-authored
        // graphs, but published NKD vocabularies type relationships only as
        // owl:ObjectProperty. Accept either — a hit on one is enough.
        Set<String> relationshipTypeIris = Set.of(OFN_NAMESPACE + VZTAH, OWL_OBJECT_PROPERTY);

        Map<String, ResolvedConceptDto> out = new HashMap<>();
        StmtIterator inSchemeStmts = model.listStatements(null, inScheme, (RDFNode) null);
        try {
            while (inSchemeStmts.hasNext()) {
                Statement stmt = inSchemeStmts.next();
                if (!stmt.getSubject().isURIResource() || !stmt.getObject().isURIResource()) {
                    continue;
                }
                String conceptIri = stmt.getSubject().getURI();
                if (out.containsKey(conceptIri)) {
                    continue;
                }
                Resource concept = stmt.getSubject().asResource();
                Resource scheme = stmt.getObject().asResource();
                Map<String, String> conceptLabels = collectMultilingual(concept, prefLabel);
                Map<String, String> schemeLabels = collectMultilingual(scheme, prefLabel);

                // Domain/range are only meaningful for relationships; carry the raw
                // target IRIs as iri-only stub DTOs for the resolver's second hop to
                // expand. Stubs never reach the cache or the wire — the resolver
                // replaces them with fully-resolved DTOs (or null) before caching.
                ResolvedConceptDto domainStub = null;
                ResolvedConceptDto rangeStub = null;
                if (hasAnyType(concept, rdfType, relationshipTypeIris)) {
                    domainStub = resourceStub(concept, rdfsDomain);
                    rangeStub = resourceStub(concept, rdfsRange);
                }

                out.put(conceptIri, ResolvedConceptDto.builder()
                        .iri(conceptIri)
                        .conceptName(conceptLabels.isEmpty() ? null : conceptLabels)
                        .ontologyIri(scheme.getURI())
                        .ontologyName(schemeLabels.isEmpty() ? null : schemeLabels)
                        .source(source)
                        .resolvedDomain(domainStub)
                        .resolvedRange(rangeStub)
                        .build());
            }
        } finally {
            inSchemeStmts.close();
        }
        return out;
    }

    private static boolean hasAnyType(Resource concept, Property rdfType, Set<String> typeIris) {
        StmtIterator types = concept.listProperties(rdfType);
        try {
            while (types.hasNext()) {
                RDFNode node = types.next().getObject();
                if (node.isURIResource() && typeIris.contains(node.asResource().getURI())) {
                    return true;
                }
            }
        } finally {
            types.close();
        }
        return false;
    }

    /** Returns an iri-only {@link ResolvedConceptDto} stub for the URI object of {@code property}, or null. */
    private static ResolvedConceptDto resourceStub(Resource concept, Property property) {
        Statement stmt = concept.getProperty(property);
        if (stmt == null || !stmt.getObject().isURIResource()) {
            return null;
        }
        return ResolvedConceptDto.builder().iri(stmt.getObject().asResource().getURI()).build();
    }

    private static Map<String, String> collectMultilingual(Resource subject, Property property) {
        Map<String, String> values = new LinkedHashMap<>();
        StmtIterator stmts = subject.listProperties(property);
        try {
            while (stmts.hasNext()) {
                RDFNode node = stmts.next().getObject();
                if (!node.isLiteral()) continue;
                Literal lit = node.asLiteral();
                String text = lit.getString();
                if (text == null || text.isEmpty()) continue;
                String lang = lit.getLanguage();
                String key = (lang == null || lang.isEmpty()) ? "" : lang;
                values.putIfAbsent(key, text);
            }
        } finally {
            stmts.close();
        }
        return values;
    }
}
