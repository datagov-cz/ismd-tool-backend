package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ConceptPropertiesModel;
import com.dia.ismdtoolbackend.models.concept.ConceptRelationshipsModel;
import com.dia.ismdtoolbackend.utility.exporter.json.ConceptData;
import com.dia.ismdtoolbackend.utility.exporter.json.ModelStructure;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDFS;

import java.util.*;
import java.util.function.Function;

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.detail.DetailDtoBuilders.*;
import static com.dia.ismdtoolbackend.utility.detail.DetailValues.*;

/** Maps the exported concept maps of a model to the ontology and concept detail models. */
class DetailModelMapper {

    private final DetailMemberReader members = new DetailMemberReader();

    OntologyDetailModel mapToOntologyDetailModel(ModelStructure structure, ConceptData conceptData,
                                                 Function<String, String> refResolver) {
        // Built once for the whole ontology: without it each concept re-scans the full concept
        // list twice (once for properties, once for relationships), which is quadratic in N.
        DetailMemberIndex memberIndex = DetailMemberIndex.build(conceptData);

        List<OntologyDetailModel.ConceptDetailModel> concepts = conceptData.getConcepts().stream()
                .map(conceptMap -> mapToConceptDetailModel(conceptMap, memberIndex, null, refResolver))
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

    /**
     * Exactly one of {@code memberIndex} (whole-ontology path) and {@code ontModel} (single-concept
     * path) is set; neither means the concept carries no members.
     */
    @SuppressWarnings("unchecked")
    OntologyDetailModel.ConceptDetailModel mapToConceptDetailModel(Map<String, Object> conceptMap,
                                                                  DetailMemberIndex memberIndex,
                                                                  OntModel ontModel,
                                                                  Function<String, String> refResolver) {
        String conceptIri = (String) conceptMap.get("iri");

        List<ConceptPropertiesModel> properties;
        List<ConceptRelationshipsModel> relationships;

        if (memberIndex != null) {
            properties = members.extractConceptProperties(conceptIri, memberIndex, refResolver);
            relationships = members.extractConceptRelationships(conceptIri, memberIndex, refResolver);
        } else if (ontModel != null) {
            properties = members.extractConceptPropertiesFromModel(ontModel, conceptIri, refResolver);
            relationships = members.extractConceptRelationshipsFromModel(ontModel, conceptIri, refResolver);
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
                .identifier(firstText(conceptMap.get(IDENTIFIKATOR)))
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
                .codeList(buildCodeList(conceptMap.get(INSTANCE_DEFINOVANY_CISELNIKEM)))
                .conceptProperties(properties)
                .conceptRelationships(relationships)
                .build();
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

    /** The concept map holds an identifier as a list (one per {@code dcterms:identifier}); the detail shows the first. */
    private static String firstText(Object value) {
        if (value instanceof List<?> values) {
            return values.isEmpty() ? null : String.valueOf(values.get(0));
        }
        return value == null ? null : value.toString();
    }
}
