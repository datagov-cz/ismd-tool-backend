package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.MatchedBy;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchSourceStatus;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class IsmdSearchProvider implements SearchProvider {

    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";
    private static final String SKOS_ALT_LABEL = "http://www.w3.org/2004/02/skos/core#altLabel";
    private static final String DCTERMS_DESCRIPTION = "http://purl.org/dc/terms/description";
    private static final String SKOS_DEFINITION = "http://www.w3.org/2004/02/skos/core#definition";
    private static final String SKOS_CONCEPT = "http://www.w3.org/2004/02/skos/core#Concept";
    private static final String SKOS_CONCEPT_SCHEME = "http://www.w3.org/2004/02/skos/core#ConceptScheme";
    private static final String OWL_ONTOLOGY = "http://www.w3.org/2002/07/owl#Ontology";

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final Executor searchExecutor;
    private final long fusekiTimeoutMs;
    private final long pgTimeoutMs;

    public IsmdSearchProvider(OntologyMetadataRepository ontologyMetadataRepository,
                              ConceptMetadataRepository conceptMetadataRepository,
                              JenaTDB2Repository jenaTDB2Repository,
                              @Qualifier("searchExecutor") Executor searchExecutor,
                              @Value("${search.fuseki-timeout-ms:10000}") long fusekiTimeoutMs,
                              @Value("${search.pg-timeout-ms:10000}") long pgTimeoutMs) {
        this.ontologyMetadataRepository = ontologyMetadataRepository;
        this.conceptMetadataRepository = conceptMetadataRepository;
        this.jenaTDB2Repository = jenaTDB2Repository;
        this.searchExecutor = searchExecutor;
        this.fusekiTimeoutMs = fusekiTimeoutMs;
        this.pgTimeoutMs = pgTimeoutMs;
    }

    @Override
    public SearchProviderResult search(String query, SearchType type, int limit, int offset,
                                       String lang, List<String> ontologyIris,
                                       List<RelationType> relationTypes, String userId,
                                       boolean isAdmin, Boolean publishedFilter) {
        List<SearchResultDto> allResults = new ArrayList<>();
        AtomicBoolean fusekiDegraded = new AtomicBoolean(false);

        // PG ontology list — matches on slug even for empty ontologies where Fuseki
        // has no indexable labels.
        if (type == null || type == SearchType.ONTOLOGY) {
            allResults.addAll(searchOntologies(query, userId, isAdmin, publishedFilter));
        }

        // Concept/scheme search against PG concept table + Fuseki text index. Fuseki
        // rows self-classify (ONTOLOGY vs CONCEPT) via rdf:type, and we filter by
        // the caller's requested type afterwards.
        allResults.addAll(searchConcepts(query, userId, isAdmin, publishedFilter, lang,
                ontologyIris, relationTypes, fusekiDegraded, limit));

        // Dedup by IRI
        LinkedHashMap<String, SearchResultDto> deduped = new LinkedHashMap<>();
        for (SearchResultDto result : allResults) {
            if (result.getIri() != null) {
                deduped.merge(result.getIri(), result, (existing, incoming) -> {
                    mergeNonNullFields(existing, incoming);
                    return existing;
                });
            }
        }

        // Type filter: apply AFTER dedup so Fuseki-classified ontologies merge with
        // PG ontology hits (and PG concepts merge with Fuseki concepts) before we
        // decide what to drop.
        List<SearchResultDto> results = deduped.values().stream()
                .filter(r -> type == null || r.getType() == type)
                .toList();

        // Apply offset and limit in-memory
        int fromIndex = Math.min(offset, results.size());
        int toIndex = Math.min(fromIndex + limit, results.size());
        List<SearchResultDto> paged = results.subList(fromIndex, toIndex);

        if (fusekiDegraded.get()) {
            return new SearchProviderResult(paged, results.size(),
                    SearchSourceStatus.DEGRADED, "Fuseki unavailable, returning PostgreSQL results only");
        }

        return new SearchProviderResult(paged, results.size());
    }

    private List<SearchResultDto> searchOntologies(String query, String userId,
                                                    boolean isAdmin, Boolean publishedFilter) {
        List<OntologyMetadataEntity> entities;
        if (Boolean.FALSE.equals(publishedFilter)) {
            entities = ontologyMetadataRepository.searchByTextUnpublished(query, userId, isAdmin);
        } else {
            entities = ontologyMetadataRepository.searchByText(query, userId);
        }

        return entities.stream()
                .map(e -> SearchResultDto.builder()
                        .iri(e.getGraphName())
                        .slug(e.getSlug())
                        .label(e.getSlug())
                        .type(SearchType.ONTOLOGY)
                        .source(SearchSource.ISMD)
                        .isPublished(e.getIsPublished())
                        .build())
                .toList();
    }

    private List<SearchResultDto> searchConcepts(String query, String userId,
                                                  boolean isAdmin, Boolean publishedFilter,
                                                  String lang,
                                                  List<String> ontologyIris,
                                                  List<RelationType> relationTypes,
                                                  AtomicBoolean fusekiDegraded, int limit) {
        boolean hasGraphFilter = ontologyIris != null && !ontologyIris.isEmpty();
        List<String> graphNames = hasGraphFilter ? ontologyIris : List.of();
        boolean unpublishedOnly = Boolean.FALSE.equals(publishedFilter);

        // Visible graph set for Fuseki. When restricted to UNPUBLISHED we narrow this
        // to the set of unpublished ontologies the user is allowed to see so every
        // Fuseki hit is implicitly inside an unpublished ontology — no per-hit
        // publish-state lookup needed.
        List<String> visibleGraphNames = unpublishedOnly
                ? getUnpublishedVisibleGraphNames(userId, isAdmin)
                : getVisibleGraphNames(userId);

        // Run PG and Fuseki text search in parallel on the dedicated search
        // executor (both are blocking I/O: JDBC + HTTP). Each future has its
        // own timeout so a slow PG query cannot extend a fast Fuseki timeout.
        CompletableFuture<List<SearchResultDto>> pgFuture = CompletableFuture.supplyAsync(() -> {
            List<ConceptMetadataEntity> entities = unpublishedOnly
                    ? conceptMetadataRepository.searchByTextUnpublished(
                            query, userId, isAdmin, hasGraphFilter, graphNames)
                    : conceptMetadataRepository.searchByText(
                            query, userId, hasGraphFilter, graphNames);
            return entities.stream()
                    .map(this::mapConceptEntity)
                    .toList();
        }, searchExecutor).orTimeout(pgTimeoutMs, TimeUnit.MILLISECONDS);

        CompletableFuture<List<SearchResultDto>> fusekiFuture = CompletableFuture.supplyAsync(() -> {
            List<String> searchGraphs = hasGraphFilter
                    ? ontologyIris.stream().filter(visibleGraphNames::contains).toList()
                    : visibleGraphNames;
            return searchFuseki(query, searchGraphs, limit);
        }, searchExecutor).orTimeout(fusekiTimeoutMs, TimeUnit.MILLISECONDS);

        List<SearchResultDto> pgResults;
        try {
            pgResults = pgFuture.join();
            log.debug("PG search returned {} results", pgResults.size());
        } catch (Exception e) {
            log.warn("PG text search failed, returning Fuseki-only results: {}", e.getMessage());
            pgResults = List.of();
        }

        List<SearchResultDto> fusekiResults;
        try {
            fusekiResults = fusekiFuture.join();
            log.debug("Fuseki search returned {} results", fusekiResults.size());
        } catch (Exception e) {
            log.warn("Fuseki text search failed, degrading to PG-only results: {}", e.getMessage());
            fusekiResults = List.of();
            fusekiDegraded.set(true);
        }

        // Track which IRIs were found by Fuseki (for matchedBy and enrichment skip)
        Set<String> fusekiIris = new HashSet<>();
        for (SearchResultDto dto : fusekiResults) {
            if (dto.getIri() != null) fusekiIris.add(dto.getIri());
        }

        // Merge PG + Fuseki results: PG first, Fuseki enriches with non-null fields
        LinkedHashMap<String, SearchResultDto> merged = new LinkedHashMap<>();
        for (SearchResultDto dto : pgResults) {
            if (dto.getIri() != null) {
                dto.setMatchedBy(MatchedBy.PG);
                merged.put(dto.getIri(), dto);
            }
        }
        for (SearchResultDto dto : fusekiResults) {
            if (dto.getIri() != null) {
                merged.merge(dto.getIri(), dto, (existing, incoming) -> {
                    mergeNonNullFields(existing, incoming);
                    existing.setMatchedBy(MatchedBy.BOTH);
                    return existing;
                });
            }
        }

        List<SearchResultDto> mergedResults = new ArrayList<>(merged.values());
        log.debug("After merge+dedup: {} results (PG={}, Fuseki={}, unique={})",
                mergedResults.size(), pgResults.size(), fusekiResults.size(), merged.size());

        // Enrich PG-only results with labels from Fuseki (skip those already enriched by text search)
        if (!fusekiDegraded.get() && !mergedResults.isEmpty()) {
            List<SearchResultDto> needsEnrichment = mergedResults.stream()
                    .filter(dto -> !fusekiIris.contains(dto.getIri()))
                    .toList();
            if (!needsEnrichment.isEmpty()) {
                try {
                    log.debug("Enriching {} PG-only results with Fuseki labels (lang={})",
                            needsEnrichment.size(), lang);
                    enrichWithLabels(needsEnrichment, lang);
                } catch (Exception e) {
                    log.warn("Fuseki label enrichment failed, using existing labels: {}", e.getMessage());
                }
            }
        }

        // Apply relation type filter if requested
        if (relationTypes != null && !relationTypes.isEmpty() && !mergedResults.isEmpty()) {
            try {
                List<String> conceptIris = mergedResults.stream()
                        .map(SearchResultDto::getIri)
                        .toList();
                log.debug("Filtering {} concepts by relation types: {}", conceptIris.size(), relationTypes);
                Set<String> matchingIris = jenaTDB2Repository.filterByRelationTypes(conceptIris, relationTypes);
                int beforeFilter = mergedResults.size();
                mergedResults = mergedResults.stream()
                        .filter(dto -> matchingIris.contains(dto.getIri()))
                        .toList();
                log.debug("Relation type filter: {} -> {} results", beforeFilter, mergedResults.size());
            } catch (Exception e) {
                log.warn("Fuseki relation type filtering failed, returning unfiltered results: {}", e.getMessage());
            }
        }

        return mergedResults;
    }

    private List<SearchResultDto> searchFuseki(String query, List<String> visibleGraphNames, int limit) {
        if (visibleGraphNames.isEmpty()) {
            log.debug("Fuseki search skipped: no visible graph names for user");
            return List.of();
        }

        log.debug("Fuseki text search: query='{}', searching {} graphs",
                query, visibleGraphNames.size());

        List<Map<String, String>> rows = jenaTDB2Repository.searchByText(query, visibleGraphNames, limit);

        log.debug("Fuseki text search returned {} raw rows", rows.size());

        List<SearchResultDto> results = new ArrayList<>();
        for (Map<String, String> row : rows) {
            SearchType resolvedType = classifyByRdfTypes(row.get("types"));
            results.add(SearchResultDto.builder()
                    .iri(row.get("resourceIri"))
                    .label(row.get("prefLabel"))
                    .labelLang(row.get("prefLabelLang"))
                    .altName(row.get("altLabel"))
                    .description(row.get("description"))
                    .definition(row.get("definition"))
                    .ontologyIri(row.get("graphName"))
                    .type(resolvedType)
                    .source(SearchSource.ISMD)
                    .matchedBy(MatchedBy.SPARQL)
                    .build());
        }
        return results;
    }

    /**
     * Classifies a Fuseki search hit by its rdf:type set (pipe-separated).
     * An ontology / concept scheme always carries skos:ConceptScheme or owl:Ontology;
     * a concept carries skos:Concept. Ambiguous or missing types fall back to CONCEPT,
     * which matches how the text index was historically consumed.
     */
    private static SearchType classifyByRdfTypes(String types) {
        if (types == null || types.isEmpty()) {
            return SearchType.CONCEPT;
        }
        boolean isOntology = false;
        boolean isConcept = false;
        for (String t : types.split("\\|")) {
            if (SKOS_CONCEPT_SCHEME.equals(t) || OWL_ONTOLOGY.equals(t)) {
                isOntology = true;
            } else if (SKOS_CONCEPT.equals(t)) {
                isConcept = true;
            }
        }
        // skos:Concept wins over skos:ConceptScheme only if the resource is not also
        // an ontology — the ontology node in our data is both ConceptScheme and Ontology
        // but never skos:Concept, so this ordering is safe.
        if (isOntology) return SearchType.ONTOLOGY;
        if (isConcept) return SearchType.CONCEPT;
        return SearchType.CONCEPT;
    }

    private void enrichWithLabels(List<SearchResultDto> results, String lang) {
        List<String> conceptIris = results.stream()
                .map(SearchResultDto::getIri)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (conceptIris.isEmpty()) return;

        Model labelsModel = jenaTDB2Repository.fetchConceptLabels(conceptIris);

        for (SearchResultDto dto : results) {
            if (dto.getIri() == null) continue;

            Resource concept = labelsModel.getResource(dto.getIri());

            // Resolve prefLabel with language preference
            String bestLabel = getLiteralWithLangPreference(concept, SKOS_PREF_LABEL, lang);
            if (bestLabel != null) {
                dto.setLabel(bestLabel);
                dto.setLabelLang(lang);
            }

            // altLabel
            String altLabel = getFirstLiteral(concept, SKOS_ALT_LABEL);
            if (altLabel != null) dto.setAltName(altLabel);

            // description
            String description = getLiteralWithLangPreference(concept, DCTERMS_DESCRIPTION, lang);
            if (description != null) dto.setDescription(description);

            // definition
            String definition = getLiteralWithLangPreference(concept, SKOS_DEFINITION, lang);
            if (definition != null) dto.setDefinition(definition);
        }
    }

    private String getLiteralWithLangPreference(Resource resource, String propertyUri, String lang) {
        Property property = resource.getModel().getProperty(propertyUri);
        StmtIterator stmts = resource.listProperties(property);

        String bestMatch = null;
        String fallback = null;

        while (stmts.hasNext()) {
            Statement stmt = stmts.next();
            if (stmt.getObject().isLiteral()) {
                Literal literal = stmt.getLiteral();
                String literalLang = literal.getLanguage();
                if (lang != null && lang.equals(literalLang)) {
                    bestMatch = literal.getString();
                } else if (fallback == null) {
                    fallback = literal.getString();
                }
            }
        }

        return bestMatch != null ? bestMatch : fallback;
    }

    private String getFirstLiteral(Resource resource, String propertyUri) {
        Property property = resource.getModel().getProperty(propertyUri);
        Statement stmt = resource.getProperty(property);
        if (stmt != null && stmt.getObject().isLiteral()) {
            return stmt.getLiteral().getString();
        }
        return null;
    }

    private List<String> getVisibleGraphNames(String userId) {
        List<OntologyMetadataEntity> ontologies = new ArrayList<>();
        ontologies.addAll(ontologyMetadataRepository.findAllByIsPublished(true));
        if (userId != null) {
            List<OntologyMetadataEntity> userOntologies = ontologyMetadataRepository.findAllByUserIdAndIsPublished(userId, false);
            ontologies.addAll(userOntologies);
        }
        return ontologies.stream()
                .map(OntologyMetadataEntity::getGraphName)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * Graph names visible to the caller when the search is restricted to
     * {@code is_published = false}. An admin sees every unpublished graph; a
     * regular user sees only their own. Used to scope Fuseki text queries so
     * every hit is transitively inside an unpublished ontology.
     */
    private List<String> getUnpublishedVisibleGraphNames(String userId, boolean isAdmin) {
        if (!isAdmin && userId == null) {
            return List.of();
        }
        return ontologyMetadataRepository.findVisibleUnpublished(userId, isAdmin).stream()
                .map(OntologyMetadataEntity::getGraphName)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private SearchResultDto mapConceptEntity(ConceptMetadataEntity e) {
        return SearchResultDto.builder()
                .iri(e.getConceptIri())
                .slug(e.getSlug())
                .label(e.getConceptName())
                .type(SearchType.CONCEPT)
                .source(SearchSource.ISMD)
                .conceptType(e.getConceptType())
                .ontologyIri(e.getGraphName())
                .isPublished(e.getIsPublished())
                .build();
    }

    private void mergeNonNullFields(SearchResultDto target, SearchResultDto source) {
        if (source.getLabel() != null) target.setLabel(source.getLabel());
        if (source.getLabelLang() != null) target.setLabelLang(source.getLabelLang());
        if (source.getAltName() != null) target.setAltName(source.getAltName());
        if (source.getDescription() != null) target.setDescription(source.getDescription());
        if (source.getDefinition() != null) target.setDefinition(source.getDefinition());
        if (source.getOntologyIri() != null) target.setOntologyIri(source.getOntologyIri());
        if (source.getConceptType() != null) target.setConceptType(source.getConceptType());
        if (source.getSlug() != null) target.setSlug(source.getSlug());
        if (source.getIsPublished() != null) target.setIsPublished(source.getIsPublished());
    }
}