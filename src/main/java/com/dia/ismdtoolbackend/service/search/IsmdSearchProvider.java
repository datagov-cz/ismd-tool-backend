package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchSourceStatus;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class IsmdSearchProvider implements SearchProvider {

    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";
    private static final String SKOS_ALT_LABEL = "http://www.w3.org/2004/02/skos/core#altLabel";
    private static final String DCTERMS_DESCRIPTION = "http://purl.org/dc/terms/description";
    private static final String SKOS_DEFINITION = "http://www.w3.org/2004/02/skos/core#definition";

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;

    @Override
    public SearchProviderResult search(String query, SearchType type, int limit, int offset,
                                       String lang, List<String> ontologyIris,
                                       List<RelationType> relationTypes, String userId) {
        List<SearchResultDto> allResults = new ArrayList<>();
        AtomicBoolean fusekiDegraded = new AtomicBoolean(false);

        if (type == null || type == SearchType.ONTOLOGY) {
            allResults.addAll(searchOntologies(query, userId));
        }

        if (type == null || type == SearchType.CONCEPT) {
            allResults.addAll(searchConcepts(query, userId, lang, ontologyIris, relationTypes, fusekiDegraded));
        }

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

        List<SearchResultDto> results = new ArrayList<>(deduped.values());

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

    private List<SearchResultDto> searchOntologies(String query, String userId) {
        List<OntologyMetadataEntity> entities = ontologyMetadataRepository.searchByText(query, userId);

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

    private List<SearchResultDto> searchConcepts(String query, String userId, String lang,
                                                  List<String> ontologyIris,
                                                  List<RelationType> relationTypes,
                                                  AtomicBoolean fusekiDegraded) {
        boolean hasGraphFilter = ontologyIris != null && !ontologyIris.isEmpty();
        List<String> graphNames = hasGraphFilter ? ontologyIris : List.of();

        // PG search only — Fuseki text search (text:query) is disabled until the
        // Jena version is upgraded to fix the text property function registration.
        // See docs/search-feature-plan.md for details.
        fusekiDegraded.set(true);

        List<ConceptMetadataEntity> entities = conceptMetadataRepository.searchByText(
                query, userId, hasGraphFilter, graphNames);
        List<SearchResultDto> mergedResults = new ArrayList<>(entities.stream()
                .map(this::mapConceptEntity)
                .toList());
        log.debug("PG search returned {} results", mergedResults.size());

        // Enrich results with labels from Fuseki (CONSTRUCT query, not text search — works fine)
        if (!mergedResults.isEmpty()) {
            try {
                log.debug("Enriching {} results with Fuseki labels (lang={})", mergedResults.size(), lang);
                enrichWithLabels(mergedResults, lang);
            } catch (Exception e) {
                log.warn("Fuseki label enrichment failed, using existing labels: {}", e.getMessage());
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
