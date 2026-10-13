package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.client.NkdSparqlClient.PublishedConcept;
import com.dia.ismdtoolbackend.config.ExternalSparqlConfig;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.query.NKDSPARQLConstructQuery;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.sparql.HttpSparqlExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static com.dia.ismdtoolbackend.client.NkdSparqlClient.PUBLISHED_RESOURCE_CACHE;

/**
 * The cached single-resource NKD fetches that {@link NkdSparqlClient}'s own cached projections build
 * on. A separate bean so those calls pass through the cache proxy.
 */
@Component
@Slf4j
class NkdPublishedResourceFetcher {

    private final HttpSparqlExecutor executor;
    private final OntologyDetailExtractor detailExtractor;

    NkdPublishedResourceFetcher(@Qualifier(ExternalSparqlConfig.NKD_SPARQL_EXECUTOR) HttpSparqlExecutor executor,
                                OntologyDetailExtractor detailExtractor) {
        this.executor = executor;
        this.detailExtractor = detailExtractor;
    }

    /**
     * Concept fetch that also surfaces the {@code skos:inScheme} target
     */
    @Cacheable(cacheNames = PUBLISHED_RESOURCE_CACHE, key = "'conceptWithScheme:' + #conceptIri")
    public Optional<PublishedConcept> fetchPublishedConceptWithScheme(String conceptIri) {
        log.debug("Fetching published concept from NKD: {}", conceptIri);
        String query = NKDSPARQLConstructQuery.buildConstructQuery(conceptIri);
        Optional<Model> resultModel = executor.construct("NKD concept fetch for " + conceptIri, query);
        if (resultModel.isEmpty()) {
            log.info("No data found for concept in NKD: {}", conceptIri);
            return Optional.empty();
        }
        Model rawModel = resultModel.get();
        log.debug("Fetched {} triples from NKD for concept: {}", rawModel.size(), conceptIri);
        String inSchemeIri = NkdSparqlClient.extractInSchemeIri(rawModel, conceptIri);
        Model processedModel = detailExtractor.applyOFNTransformationsForNkd(rawModel);
        OntologyDetailModel.ConceptDetailModel conceptDetail =
                detailExtractor.extractConceptDetail(processedModel, conceptIri,
                        OntologyDetailExtractor.iriResolver());
        log.debug("Successfully extracted published concept detail from NKD: {} (inScheme={})",
                conceptIri, inSchemeIri);
        return Optional.of(new PublishedConcept(conceptDetail, inSchemeIri));
    }

    /**
     * Raw NKD ontology model — same CONSTRUCT as {@link NkdSparqlClient#fetchPublishedOntology}
     * but returned before OFN extraction, for callers that need to serialize
     * the source RDF (e.g. download endpoint).
     *
     * <p>Cached under its own key so the ontology deviation check and
     * {@link NkdSparqlClient#derivePublishedConceptsFromOntology} share a single round-trip: the
     * CONSTRUCT already carries every in-scheme concept, so the concept side needs no query of its
     * own. Callers must treat the returned model as read-only — it is the shared cached instance.
     */
    @Cacheable(cacheNames = PUBLISHED_RESOURCE_CACHE, key = "'ontologyRaw:' + #ontologyIri")
    public Optional<Model> fetchPublishedOntologyRaw(String ontologyIri) {
        log.debug("Fetching raw NKD ontology model: {}", ontologyIri);
        String query = NKDSPARQLConstructQuery.buildOntologyConstructQuery(ontologyIri);
        Optional<Model> resultModel = executor.construct("NKD ontology fetch for " + ontologyIri, query);
        if (resultModel.isEmpty()) {
            log.info("No data found for ontology in NKD: {}", ontologyIri);
            return Optional.empty();
        }
        log.debug("Fetched {} triples from NKD for ontology: {}", resultModel.get().size(), ontologyIri);
        return resultModel;
    }
}
