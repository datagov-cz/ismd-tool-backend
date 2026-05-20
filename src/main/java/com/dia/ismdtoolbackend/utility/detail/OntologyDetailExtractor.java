package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto.EnrichmentStatus;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ConceptPropertiesModel;
import com.dia.ismdtoolbackend.models.concept.ConceptRelationshipsModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaCzechCitationFormatter;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import com.dia.ismdtoolbackend.utility.exporter.json.ConceptData;
import com.dia.ismdtoolbackend.utility.exporter.json.ConceptProcessor;
import com.dia.ismdtoolbackend.utility.exporter.json.ModelAnalyzer;
import com.dia.ismdtoolbackend.utility.exporter.json.ModelStructure;
import com.dia.ismdtoolbackend.utility.exporter.turtle.OFNTypeNormalizer;
import com.dia.ismdtoolbackend.utility.exporter.turtle.TurtleFilterUtil;
import com.dia.ismdtoolbackend.utility.exporter.turtle.TurtleFormatterUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.constants.VocabularyConstants.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class OntologyDetailExtractor {

    private final ConceptMetadataRepository conceptMetadataRepository;

    /**
     * Resolver that maps a concept IRI to its local DB slug, or {@code null} when the concept
     * has no local metadata row. Intended for the local detail pipeline.
     */
    public Function<String, String> dbSlugResolver() {
        return iri -> conceptMetadataRepository.findByConceptIri(iri)
                .map(ConceptMetadataEntity::getSlug)
                .orElse(null);
    }

    /**
     * Resolver that returns the IRI unchanged. Intended for the NKD detail pipeline,
     * where the navigation reference is the full IRI used by /api/nkd/.../detail endpoints.
     */
    public static Function<String, String> iriResolver() {
        return iri -> iri;
    }

    public Model applyOFNTransformations(Model rawModel) {
        log.debug("Applying OFN transformations");
        // Normalize before filtering: NKD-published concepts often carry only
        // generic types (slovníky:pojem + owl:Class/ObjectProperty/DatatypeProperty),
        // all of which TurtleFilterUtil treats as "vocabulary noise" and would
        // strip. Inferring the role tags (skos:Concept, slovníky:třída/vztah/
        // vlastnost) here keeps real concepts past the filter.
        int normalized = OFNTypeNormalizer.normalize(rawModel);
        if (normalized > 0) {
            log.debug("Inferred OFN role tags on {} resources before filtering", normalized);
        }
        Model filteredModel = TurtleFilterUtil.createFilteredModel(rawModel);
        Model ofnFormattedModel = TurtleFormatterUtil.transformToOFNFormat(filteredModel);
        log.debug("OFN transformation complete: {} -> {} -> {} statements",
                rawModel.size(), filteredModel.size(), ofnFormattedModel.size());
        return ofnFormattedModel;
    }

   @Transactional(readOnly = true)
    public OntologyDetailModel extractOntologyDetail(Model processedModel) {
        return extractOntologyDetail(processedModel, dbSlugResolver());
    }

    public OntologyDetailModel extractOntologyDetail(Model processedModel, Function<String, String> refResolver) {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, processedModel);

        ModelAnalyzer modelAnalyzer = new ModelAnalyzer();
        ConceptProcessor conceptProcessor = new ConceptProcessor();

        ModelStructure structure = modelAnalyzer.analyzeModel(processedModel);
        ConceptData conceptData = conceptProcessor.processAllConcepts(ontModel, structure);

        return mapToOntologyDetailModel(structure, conceptData, refResolver);
    }

    public OntologyDetailModel.ConceptDetailModel extractConceptDetail(Model processedModel, String conceptIri) {
        return extractConceptDetail(processedModel, conceptIri, dbSlugResolver());
    }

    public OntologyDetailModel.ConceptDetailModel extractConceptDetail(Model processedModel, String conceptIri,
                                                                      Function<String, String> refResolver) {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, processedModel);

        ModelAnalyzer modelAnalyzer = new ModelAnalyzer();
        ConceptProcessor conceptProcessor = new ConceptProcessor();

        ModelStructure structure = modelAnalyzer.analyzeModel(processedModel);
        Map<String, Object> conceptMap = conceptProcessor.processConceptByIri(ontModel, structure, conceptIri);

        return mapToConceptDetailModel(conceptMap, null, ontModel, refResolver);
    }

    private OntologyDetailModel mapToOntologyDetailModel(ModelStructure structure, ConceptData conceptData,
                                                         Function<String, String> refResolver) {
        List<OntologyDetailModel.ConceptDetailModel> concepts = conceptData.getConcepts().stream()
                .map(conceptMap -> mapToConceptDetailModel(conceptMap, conceptData, null, refResolver))
                .toList();

        Map<String, String> descriptionMap = extractMultilingualDescription(structure.getVocabularyResource());

        return OntologyDetailModel.builder()
                .context(CONTEXT_JSONLD)
                .iri(structure.getOntologyIRI())
                .types(structure.getVocabularyTypes())
                .name(createMultilingualMap(structure.getModelName()))
                .description(descriptionMap)
                .creationDate(structure.getCreationDate())
                .modificationDate(structure.getModificationDate())
                .concepts(concepts)
                .build();
    }

    public List<ConceptPropertiesModel> extractConceptProperties(String conceptIri, ConceptData conceptData,
                                                                 Function<String, String> refResolver) {
        List<ConceptPropertiesModel> properties = new ArrayList<>();

        for (Map<String, Object> conceptMap : conceptData.getConcepts()) {
            @SuppressWarnings("unchecked")
            List<String> types = (List<String>) conceptMap.get("typ");
            String propertyIri = (String) conceptMap.get("iri");

            if (types != null && types.contains("Vlastnost")) {
                Object domainObj = conceptMap.get(DEFINICNI_OBOR);
                String domain = domainObj instanceof String ? (String) domainObj : null;

                if (conceptIri.equals(domain)) {
                    ConceptPropertiesModel propertyModel = new ConceptPropertiesModel();

                    @SuppressWarnings("unchecked")
                    Map<String, String> nameMap = (Map<String, String>) conceptMap.get(NAZEV);
                    String name = extractFirstAvailableName(nameMap);

                    propertyModel.setName(name);
                    propertyModel.setRef(refResolver.apply(propertyIri));

                    properties.add(propertyModel);
                }
            }
        }

        return properties;
    }

    public List<ConceptPropertiesModel> extractConceptPropertiesFromModel(OntModel ontModel, String conceptIri,
                                                                         Function<String, String> refResolver) {
        List<ConceptPropertiesModel> properties = new ArrayList<>();

        Resource conceptResource = ontModel.getResource(conceptIri);
        Resource vlastnostType = ontModel.getResource(OFN_NAMESPACE + VLASTNOST);

        ResIterator propertyIterator = ontModel.listSubjectsWithProperty(
            org.apache.jena.vocabulary.RDFS.domain,
            conceptResource
        );

        while (propertyIterator.hasNext()) {
            Resource propertyResource = propertyIterator.next();

            if (propertyResource.hasProperty(org.apache.jena.rdf.model.ResourceFactory.createProperty(
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "type"), vlastnostType)) {

                ConceptPropertiesModel propertyModel = new ConceptPropertiesModel();
                String propertyIri = propertyResource.getURI();

                Statement nameStmt = propertyResource.getProperty(org.apache.jena.vocabulary.SKOS.prefLabel);
                String name = nameStmt != null ? nameStmt.getString() : null;

                propertyModel.setName(name);
                propertyModel.setRef(refResolver.apply(propertyIri));

                properties.add(propertyModel);
            }
        }

        return properties;
    }

    public List<ConceptRelationshipsModel> extractConceptRelationships(String conceptIri, ConceptData conceptData,
                                                                      Function<String, String> refResolver) {
        List<ConceptRelationshipsModel> relationships = new ArrayList<>();

        for (Map<String, Object> conceptMap : conceptData.getConcepts()) {
            @SuppressWarnings("unchecked")
            List<String> types = (List<String>) conceptMap.get("typ");
            String relationshipIri = (String) conceptMap.get("iri");

            if (types != null && types.contains("Vztah")) {
                Object domainObj = conceptMap.get(DEFINICNI_OBOR);
                String domain = domainObj instanceof String ? (String) domainObj : null;

                if (conceptIri.equals(domain)) {
                    ConceptRelationshipsModel relationshipModel = new ConceptRelationshipsModel();

                    @SuppressWarnings("unchecked")
                    Map<String, String> nameMap = (Map<String, String>) conceptMap.get(NAZEV);
                    String name = extractFirstAvailableName(nameMap);

                    relationshipModel.setName(name);
                    relationshipModel.setRef(refResolver.apply(relationshipIri));

                    relationships.add(relationshipModel);
                }
            }
        }

        return relationships;
    }

    public List<ConceptRelationshipsModel> extractConceptRelationshipsFromModel(OntModel ontModel, String conceptIri,
                                                                               Function<String, String> refResolver) {
        List<ConceptRelationshipsModel> relationships = new ArrayList<>();

        Resource conceptResource = ontModel.getResource(conceptIri);
        Resource vztahType = ontModel.getResource(OFN_NAMESPACE + VZTAH);

        ResIterator relationshipIterator = ontModel.listSubjectsWithProperty(
            org.apache.jena.vocabulary.RDFS.domain,
            conceptResource
        );

        while (relationshipIterator.hasNext()) {
            Resource relationshipResource = relationshipIterator.next();

            if (relationshipResource.hasProperty(ResourceFactory.createProperty(
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "type"), vztahType)) {

                ConceptRelationshipsModel relationshipModel = new ConceptRelationshipsModel();
                String relationshipIri = relationshipResource.getURI();

                Statement nameStmt = relationshipResource.getProperty(SKOS.prefLabel);
                String name = nameStmt != null ? nameStmt.getString() : null;

                relationshipModel.setName(name);
                relationshipModel.setRef(refResolver.apply(relationshipIri));

                relationships.add(relationshipModel);
            }
        }

        return relationships;
    }

    private String extractFirstAvailableName(Map<String, String> nameMap) {
        if (nameMap == null || nameMap.isEmpty()) {
            return null;
        }

        if (nameMap.containsKey("cs")) {
            return nameMap.get("cs");
        }

        return nameMap.values().iterator().next();
    }

    @SuppressWarnings("unchecked")
    private OntologyDetailModel.ConceptDetailModel mapToConceptDetailModel(Map<String, Object> conceptMap,
                                                                          ConceptData conceptData,
                                                                          OntModel ontModel,
                                                                          Function<String, String> refResolver) {
        String conceptIri = (String) conceptMap.get("iri");

        List<ConceptPropertiesModel> properties;
        List<ConceptRelationshipsModel> relationships;

        if (conceptData != null) {
            properties = extractConceptProperties(conceptIri, conceptData, refResolver);
            relationships = extractConceptRelationships(conceptIri, conceptData, refResolver);
        } else if (ontModel != null) {
            properties = extractConceptPropertiesFromModel(ontModel, conceptIri, refResolver);
            relationships = extractConceptRelationshipsFromModel(ontModel, conceptIri, refResolver);
        } else {
            properties = Collections.emptyList();
            relationships = Collections.emptyList();
        }

        return OntologyDetailModel.ConceptDetailModel.builder()
                .iri(conceptIri)
                .types((List<String>) conceptMap.get("typ"))
                .name((Map<String, String>) conceptMap.get(NAZEV))
                .alternativeName((Map<String, Object>) conceptMap.get(ALTERNATIVNI_NAZEV))
                .definition((Map<String, String>) conceptMap.get(DEFINICE))
                .description((Map<String, String>) conceptMap.get(POPIS))
                .identifier((String) conceptMap.get(IDENTIFIKATOR))
                .exactMatches((List<String>) conceptMap.get(EKVIVALENTNI_POJEM))
                .domain((String) conceptMap.get(DEFINICNI_OBOR))
                .range((String) conceptMap.get(OBOR_HODNOT))
                .broaderClasses((List<String>) conceptMap.get(NADRAZENA_TRIDA))
                .broaderRelations((List<String>) conceptMap.get(NADRAZENY_VZTAH))
                .broaderProperties((List<String>) conceptMap.get(NADRAZENA_VLASTNOST))
                .definingLegalSources((List<String>) conceptMap.get(DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU))
                .relatedLegalSources((List<String>) conceptMap.get(SOUVISEJICI_USTANOVENI_PRAVNIHO_PREDPISU))
                .definingLegalSourcesResolved(buildResolvedSources(
                        (List<String>) conceptMap.get(DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU)))
                .relatedLegalSourcesResolved(buildResolvedSources(
                        (List<String>) conceptMap.get(SOUVISEJICI_USTANOVENI_PRAVNIHO_PREDPISU)))
                .definingNonLegalSources((List<Map<String, Object>>) conceptMap.get(DEFINUJICI_NELEGISLATIVNI_ZDROJ))
                .relatedNonLegalSources((List<Map<String, Object>>) conceptMap.get(SOUVISEJICI_NELEGISLATIVNI_ZDROJ))
                .sharingMethods((List<String>) conceptMap.get(ZPUSOBY_SDILENI_ALT))
                .acquisitionMethod(extractStringFromValue(conceptMap.get(ZPUSOB_ZISKANI_ALT)))
                .contentType(extractStringFromValue(conceptMap.get(TYP_OBSAHU_ALT)))
                .isPpdf((Boolean) conceptMap.get(JE_PPDF))
                .ais(extractStringFromValue(conceptMap.get(AIS)))
                .agenda(extractStringFromValue(conceptMap.get(AGENDA)))
                .privacyProvisions((List<String>) conceptMap.get(USTANOVENI_NEVEREJNOST))
                .conceptProperties(properties)
                .conceptRelationships(relationships)
                .build();
    }

    private String extractStringFromValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String) {
                return (String) list.get(0);
            }

        return null;
    }

    private Map<String, String> createMultilingualMap(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        map.put(DEFAULT_LANG, value);
        return map;
    }

    private Map<String, String> extractMultilingualDescription(Resource vocabularyResource) {
        if (vocabularyResource == null) {
            return Collections.emptyMap();
        }

        Map<String, String> descriptionMap = new LinkedHashMap<>();
        Property descProperty = ResourceFactory.createProperty(DCT_NS + "description");

        StmtIterator iter = vocabularyResource.listProperties(descProperty);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            if (stmt.getObject().isLiteral()) {
                Literal literal = stmt.getObject().asLiteral();
                String lang = literal.getLanguage();
                String value = literal.getString();

                if (value != null && !value.trim().isEmpty()) {
                    String languageTag = (lang != null && !lang.isEmpty()) ? lang : DEFAULT_LANG;
                    descriptionMap.put(languageTag, value);
                }
            }
        }

        return descriptionMap;
    }

    /**
     * Build parse-only resolved-source DTOs for the concept-detail response.
     * Returns {@code null} when the input is null/empty so {@code @JsonInclude(NON_NULL)}
     * omits the field. Never calls SPARQL — fragment URLs are flagged
     * {@code PENDING} for the FE to enrich via {@code /api/eli/resolve}.
     */
    static List<ResolvedLegalSourceDto> buildResolvedSources(List<String> urls) {
        if (urls == null || urls.isEmpty()) return null;
        List<ResolvedLegalSourceDto> out = new ArrayList<>(urls.size());
        for (String url : urls) {
            out.add(parseUrlToPendingDto(url));
        }
        return out;
    }

    private static ResolvedLegalSourceDto parseUrlToPendingDto(String url) {
        ParsedEli parsed = EsbirkaEliParser.parse(url);
        if (!parsed.isValid()) {
            return ResolvedLegalSourceDto.builder()
                    .originalUrl(url)
                    .enrichmentStatus(EnrichmentStatus.INVALID_IRI)
                    .build();
        }
        EnrichmentStatus status = parsed.isFragment()
                ? EnrichmentStatus.PENDING
                : EnrichmentStatus.SKIPPED_NON_FRAGMENT;
        return ResolvedLegalSourceDto.builder()
                .originalUrl(url)
                .domain(parsed.domain())
                .eliPath(parsed.eliPath())
                .level(parsed.level())
                .lawIri(parsed.lawIri())
                .versionIri(parsed.versionIri())
                .fragmentIri(parsed.fragmentIri())
                .lawNumber(parsed.lawNumber())
                .lawYear(parsed.lawYear())
                .sbirkaCode(parsed.sbirkaCode())
                .versionDate(parsed.versionDate())
                .fragmentSegments(parsed.fragmentSegments())
                .displayLabel(EsbirkaCzechCitationFormatter.buildDisplayLabel(parsed, null))
                .enrichmentStatus(status)
                .build();
    }
}
