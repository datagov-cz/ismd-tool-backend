package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.controller.dto.DataTypeDto;
import com.dia.ismdtoolbackend.controller.dto.NonLegalSourceDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto.EnrichmentStatus;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.PropertyDataType;
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
import org.apache.jena.vocabulary.RDFS;
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

    /**
     * Local-detail OFN transform. Infers role tags + labels but does NOT derive
     * {@code skos:inScheme}: local vocabularies are written via the authoritative
     * upload path, which guarantees an explicit inScheme on every owned concept.
     */
    public Model applyOFNTransformations(Model rawModel) {
        return applyOFNTransformations(rawModel, false);
    }

    /**
     * NKD-detail OFN transform. Same as the local transform PLUS {@code skos:inScheme}
     * derivation, since NKD data is out of our control and often arrives without one.
     */
    public Model applyOFNTransformationsForNkd(Model rawModel) {
        return applyOFNTransformations(rawModel, true);
    }

    private Model applyOFNTransformations(Model rawModel, boolean deriveInScheme) {
        log.debug("Applying OFN transformations (deriveInScheme={})", deriveInScheme);
        // Normalize before filtering: NKD-published concepts often carry only
        // generic types (slovníky:pojem + owl:Class/ObjectProperty/DatatypeProperty),
        // all of which TurtleFilterUtil treats as "vocabulary noise" and would
        // strip. Inferring the role tags (skos:Concept, slovníky:třída/vztah/
        // vlastnost) here keeps real concepts past the filter.
        int normalized = deriveInScheme
                ? OFNTypeNormalizer.normalizeForNkd(rawModel)
                : OFNTypeNormalizer.normalizeForLocalDetail(rawModel);
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
                .context(CONTEXT)
                .iri(structure.getOntologyIRI())
                .types(structure.getVocabularyTypes())
                .name(extractMultilingualName(structure.getVocabularyResource()))
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

                    Map<String, String> nameMap = coerceToStringMap(conceptMap.get(NAZEV), conceptIri, NAZEV);
                    String name = extractFirstAvailableName(nameMap);

                    propertyModel.setName(name);
                    propertyModel.setIri(propertyIri);
                    propertyModel.setRef(refResolver.apply(propertyIri));

                    Object rawRange = conceptMap.get(OBOR_HODNOT);
                    propertyModel.setRange(rawRange instanceof String s ? s : null);
                    propertyModel.setRangeResolved(buildDataTypeDto(rawRange));

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
                propertyModel.setIri(propertyIri);
                propertyModel.setRef(refResolver.apply(propertyIri));

                String rawRange = extractRawRange(propertyResource);
                propertyModel.setRange(rawRange);
                propertyModel.setRangeResolved(buildDataTypeDto(rawRange));

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

                Object rangeObj = conceptMap.get(OBOR_HODNOT);
                String range = rangeObj instanceof String ? (String) rangeObj : null;

                if (conceptIri.equals(domain) || conceptIri.equals(range)) {
                    ConceptRelationshipsModel relationshipModel = new ConceptRelationshipsModel();

                    Map<String, String> nameMap = coerceToStringMap(conceptMap.get(NAZEV), relationshipIri, NAZEV);
                    String name = extractFirstAvailableName(nameMap);

                    relationshipModel.setName(name);
                    relationshipModel.setIri(relationshipIri);
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

        Set<Resource> relationshipResources = new LinkedHashSet<>();
        ontModel.listSubjectsWithProperty(org.apache.jena.vocabulary.RDFS.domain, conceptResource)
                .forEachRemaining(relationshipResources::add);
        ontModel.listSubjectsWithProperty(org.apache.jena.vocabulary.RDFS.range, conceptResource)
                .forEachRemaining(relationshipResources::add);

        for (Resource relationshipResource : relationshipResources) {
            if (relationshipResource.hasProperty(ResourceFactory.createProperty(
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "type"), vztahType)) {

                ConceptRelationshipsModel relationshipModel = new ConceptRelationshipsModel();
                String relationshipIri = relationshipResource.getURI();

                Statement nameStmt = relationshipResource.getProperty(SKOS.prefLabel);
                String name = nameStmt != null ? nameStmt.getString() : null;

                relationshipModel.setName(name);
                relationshipModel.setIri(relationshipIri);
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

    /**
     * Coerce a raw multilingual property value from the {@link ConceptProcessor} export map
     * into a strict {@code Map<String, String>} (lang → single label).
     *
     * <p>{@code ConceptProcessor.addValueToLanguageMap} promotes a language entry to a
     * {@code List} when a concept carries more than one value for the same language
     * (e.g. two {@code skos:prefLabel @cs}). The detail contract is single-valued, so we
     * keep the first String and warn-log the dropped extras. Without this, the raw
     * {@code (Map<String, String>)} cast slips a {@code List} past type erasure and Jackson
     * later throws {@link ClassCastException} while serializing the whole ontology detail.
     */
    private Map<String, String> coerceToStringMap(Object value, String conceptIri, String field) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String lang)) {
                continue;
            }
            Object v = e.getValue();
            if (v instanceof String s) {
                out.put(lang, s);
            } else if (v instanceof List<?> list) {
                String first = list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .findFirst()
                        .orElse(null);
                if (first != null) {
                    out.put(lang, first);
                    if (list.size() > 1) {
                        log.warn("Concept {} has {} '{}' values for lang '{}'; keeping first, dropping {} extra(s)",
                                conceptIri, list.size(), field, lang, list.size() - 1);
                    }
                }
            }
        }
        return out.isEmpty() ? null : out;
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

        List<String> conceptTypes = (List<String>) conceptMap.get("typ");
        boolean isVlastnost = conceptTypes != null && conceptTypes.contains("Vlastnost");

        return OntologyDetailModel.ConceptDetailModel.builder()
                .iri(conceptIri)
                .types(conceptTypes)
                .name(coerceToStringMap(conceptMap.get(NAZEV), conceptIri, NAZEV))
                .alternativeName((Map<String, Object>) conceptMap.get(ALTERNATIVNI_NAZEV))
                .definition(coerceToStringMap(conceptMap.get(DEFINICE), conceptIri, DEFINICE))
                .description(coerceToStringMap(conceptMap.get(POPIS), conceptIri, POPIS))
                .identifier((String) conceptMap.get(IDENTIFIKATOR))
                .exactMatches((List<String>) conceptMap.get(EKVIVALENTNI_POJEM))
                .domain((String) conceptMap.get(DEFINICNI_OBOR))
                .range((String) conceptMap.get(OBOR_HODNOT))
                .rangeResolved(isVlastnost ? buildDataTypeDto(conceptMap.get(OBOR_HODNOT)) : null)
                .broaderClasses((List<String>) conceptMap.get(NADRAZENA_TRIDA))
                .broaderRelations((List<String>) conceptMap.get(NADRAZENY_VZTAH))
                .broaderProperties((List<String>) conceptMap.get(NADRAZENA_VLASTNOST))
                .definingLegalSources(nullToEmpty((List<String>) conceptMap.get(DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU)))
                .relatedLegalSources(nullToEmpty((List<String>) conceptMap.get(SOUVISEJICI_USTANOVENI_PRAVNIHO_PREDPISU)))
                .definingLegalSourcesResolved(buildResolvedSources(
                        (List<String>) conceptMap.get(DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU)))
                .relatedLegalSourcesResolved(buildResolvedSources(
                        (List<String>) conceptMap.get(SOUVISEJICI_USTANOVENI_PRAVNIHO_PREDPISU)))
                .definingNonLegalSources(buildNonLegalSources(
                        (List<Map<String, Object>>) conceptMap.get(DEFINUJICI_NELEGISLATIVNI_ZDROJ)))
                .relatedNonLegalSources(buildNonLegalSources(
                        (List<Map<String, Object>>) conceptMap.get(SOUVISEJICI_NELEGISLATIVNI_ZDROJ)))
                .sharingMethods((List<String>) conceptMap.get(ZPUSOBY_SDILENI_ALT))
                .acquisitionMethod(extractStringFromValue(conceptMap.get(ZPUSOB_ZISKANI_ALT)))
                .contentType(extractStringFromValue(conceptMap.get(TYP_OBSAHU_ALT)))
                .isPpdf((Boolean) conceptMap.get(JE_PPDF))
                .ais(extractStringFromValue(conceptMap.get(AIS)))
                .agenda(extractStringFromValue(conceptMap.get(AGENDA)))
                .privacyProvisions(nullToEmpty((List<String>) conceptMap.get(USTANOVENI_NEVEREJNOST)))
                .privacyProvisionsResolved(buildResolvedSources((List<String>) conceptMap.get(USTANOVENI_NEVEREJNOST)))
                .conceptProperties(properties)
                .conceptRelationships(relationships)
                .build();
    }

    static List<NonLegalSourceDto> buildNonLegalSources(List<Map<String, Object>> rawSources) {
        if (rawSources == null || rawSources.isEmpty()) {
            return List.of();
        }
        List<NonLegalSourceDto> out = new ArrayList<>(rawSources.size());
        for (Map<String, Object> src : rawSources) {
            if (src == null) {
                continue;
            }
            out.add(NonLegalSourceDto.builder()
                    .iri(asString(src.get("iri")))
                    .typ(asString(src.get("typ")))
                    .nazev(asMultilingualMap(src.get(NAZEV)))
                    .popis(asMultilingualMap(src.get(POPIS)))
                    .url(asString(src.get("url")))
                    .build());
        }
        return out;
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static Map<String, String> asMultilingualMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() instanceof String key && e.getValue() instanceof String val) {
                out.put(key, val);
            }
        }
        return out.isEmpty() ? null : out;
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

    /**
     * Collect all language-tagged literal values of the given {@code properties}
     * on {@code vocabularyResource} into a {@code lang -> value} map, preserving
     * every language variant. Untagged literals key under {@code DEFAULT_LANG}.
     * Properties are read in order and the first non-blank value for a language
     * wins, so list more authoritative predicates first.
     *
     * <p>Shared by the ontology name and description reads — both carry multiple
     * language variants. The single-language collapse this replaced was why only
     * the {@code cs} name variant surfaced in the detail response.
     */
    private Map<String, String> extractMultilingualValue(Resource vocabularyResource, Property... properties) {
        if (vocabularyResource == null) {
            return Collections.emptyMap();
        }

        Map<String, String> valuesByLang = new LinkedHashMap<>();
        for (Property property : properties) {
            StmtIterator iter = vocabularyResource.listProperties(property);
            while (iter.hasNext()) {
                Statement stmt = iter.next();
                if (!stmt.getObject().isLiteral()) {
                    continue;
                }
                Literal literal = stmt.getObject().asLiteral();
                String value = literal.getString();
                if (value == null || value.trim().isEmpty()) {
                    continue;
                }
                String lang = literal.getLanguage();
                String languageTag = (lang != null && !lang.isEmpty()) ? lang : DEFAULT_LANG;
                valuesByLang.putIfAbsent(languageTag, value);
            }
        }

        return valuesByLang;
    }

    private Map<String, String> extractMultilingualName(Resource vocabularyResource) {
        // Same predicates ModelAnalyzer recognises as the vocabulary name; rdfs:label first.
        return extractMultilingualValue(vocabularyResource,
                ResourceFactory.createProperty(RDFS.getURI() + "label"),
                ResourceFactory.createProperty(SKOS_NS + "prefLabel"));
    }

    private Map<String, String> extractMultilingualDescription(Resource vocabularyResource) {
        return extractMultilingualValue(vocabularyResource,
                ResourceFactory.createProperty(DCT_NS + "description"));
    }

    /**
     * Build parse-only resolved-source DTOs for the concept-detail response.
     * Returns an empty list (never null) when the input is null/empty, so the
     * field always serializes as {@code []}. Never calls SPARQL — fragment URLs
     * are flagged {@code PENDING} for the FE to enrich via {@code /api/eli/resolve}.
     * <p>
     * Read {@code rdfs:range} from a property {@code Resource} and emit it in
     * the same shape {@code ConceptProcessor.addDomainAndRange} writes to the
     * JSON map: abbreviated to {@code xsd:*} when in the XSD namespace, else
     * the raw URI. Returns {@code null} when no range is set.
     */
    static String extractRawRange(Resource propertyResource) {
        Statement rangeStmt = propertyResource.getProperty(org.apache.jena.vocabulary.RDFS.range);
        if (rangeStmt == null || !rangeStmt.getObject().isResource()) {
            return null;
        }
        String rangeUri = rangeStmt.getObject().asResource().getURI();
        if (rangeUri == null) {
            return null;
        }
        if (rangeUri.startsWith(XSD)) {
            return "xsd:" + rangeUri.substring(XSD.length());
        }
        return rangeUri;
    }

    /**
     * Resolve any raw range value (bare code, {@code xsd:}/{@code rdfs:} prefix,
     * full IRI, Czech label) to a codelist DTO. Falls back to {@code Literal}
     * for null/empty/unrecognised input — mirrors the write-path default in
     * {@code ConceptCreator.addRangeProperty}.
     */
    static DataTypeDto buildDataTypeDto(Object rawRange) {
        String raw = rawRange instanceof String s ? s : null;
        return PropertyDataType.fromValue(raw)
                .orElse(PropertyDataType.LITERAL)
                .toDto();
    }

    static List<ResolvedLegalSourceDto> buildResolvedSources(List<String> urls) {
        if (urls == null || urls.isEmpty()) return List.of();
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
