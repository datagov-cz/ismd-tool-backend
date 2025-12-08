package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ConceptPropertiesModel;
import com.dia.ismdtoolbackend.models.concept.ConceptRelationshipsModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.utility.exporter.json.ConceptData;
import com.dia.ismdtoolbackend.utility.exporter.json.ConceptProcessor;
import com.dia.ismdtoolbackend.utility.exporter.json.ModelAnalyzer;
import com.dia.ismdtoolbackend.utility.exporter.json.ModelStructure;
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

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.constants.VocabularyConstants.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class OntologyDetailExtractor {

    private final ConceptMetadataRepository conceptMetadataRepository;

    public Model applyOFNTransformations(Model rawModel) {
        log.debug("Applying OFN transformations");
        Model filteredModel = TurtleFilterUtil.createFilteredModel(rawModel);
        Model ofnFormattedModel = TurtleFormatterUtil.transformToOFNFormat(filteredModel);
        log.debug("OFN transformation complete: {} -> {} -> {} statements",
                rawModel.size(), filteredModel.size(), ofnFormattedModel.size());
        return ofnFormattedModel;
    }

   @Transactional(readOnly = true)
    public OntologyDetailModel extractOntologyDetail(Model processedModel) {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, processedModel);

        ModelAnalyzer modelAnalyzer = new ModelAnalyzer();
        ConceptProcessor conceptProcessor = new ConceptProcessor();

        ModelStructure structure = modelAnalyzer.analyzeModel(processedModel);
        ConceptData conceptData = conceptProcessor.processAllConcepts(ontModel, structure);

        return mapToOntologyDetailModel(structure, conceptData);
    }

    public OntologyDetailModel.ConceptDetailModel extractConceptDetail(Model processedModel, String conceptIri) {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, processedModel);

        ModelAnalyzer modelAnalyzer = new ModelAnalyzer();
        ConceptProcessor conceptProcessor = new ConceptProcessor();

        ModelStructure structure = modelAnalyzer.analyzeModel(processedModel);
        Map<String, Object> conceptMap = conceptProcessor.processConceptByIri(ontModel, structure, conceptIri);

        return mapToConceptDetailModel(conceptMap, null, ontModel);
    }

    private OntologyDetailModel mapToOntologyDetailModel(ModelStructure structure, ConceptData conceptData) {
        List<OntologyDetailModel.ConceptDetailModel> concepts = conceptData.getConcepts().stream()
                .map(conceptMap -> mapToConceptDetailModel(conceptMap, conceptData))
                .toList();

        return OntologyDetailModel.builder()
                .context(CONTEXT_JSONLD)
                .iri(structure.getOntologyIRI())
                .types(structure.getVocabularyTypes())
                .name(createMultilingualMap(structure.getModelName()))
                .description(createMultilingualMap(structure.getModelDescription()))
                .creationDate(structure.getCreationDate())
                .modificationDate(structure.getModificationDate())
                .concepts(concepts)
                .build();
    }

    public List<ConceptPropertiesModel> extractConceptProperties(String conceptIri, ConceptData conceptData) {
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
                    Optional<ConceptMetadataEntity> concept = conceptMetadataRepository.findByConceptIri(propertyIri);
                    propertyModel.setSlug(concept.map(ConceptMetadataEntity::getSlug).orElse(null));

                    properties.add(propertyModel);
                }
            }
        }

        return properties;
    }

    public List<ConceptPropertiesModel> extractConceptPropertiesFromModel(OntModel ontModel, String conceptIri) {
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
                Optional<ConceptMetadataEntity> concept = conceptMetadataRepository.findByConceptIri(propertyIri);
                propertyModel.setSlug(concept.map(ConceptMetadataEntity::getSlug).orElse(null));

                properties.add(propertyModel);
            }
        }

        return properties;
    }

    public List<ConceptRelationshipsModel> extractConceptRelationships(String conceptIri, ConceptData conceptData) {
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
                    Optional<ConceptMetadataEntity> concept = conceptMetadataRepository.findByConceptIri(relationshipIri);
                    relationshipModel.setSlug(concept.map(ConceptMetadataEntity::getSlug).orElse(null));

                    relationships.add(relationshipModel);
                }
            }
        }

        return relationships;
    }

    public List<ConceptRelationshipsModel> extractConceptRelationshipsFromModel(OntModel ontModel, String conceptIri) {
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
                Optional<ConceptMetadataEntity> concept = conceptMetadataRepository.findByConceptIri(relationshipIri);
                relationshipModel.setSlug(concept.map(ConceptMetadataEntity::getSlug).orElse(null));

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

    private OntologyDetailModel.ConceptDetailModel mapToConceptDetailModel(Map<String, Object> conceptMap, ConceptData conceptData) {
        return mapToConceptDetailModel(conceptMap, conceptData, null);
    }

    @SuppressWarnings("unchecked")
    private OntologyDetailModel.ConceptDetailModel mapToConceptDetailModel(Map<String, Object> conceptMap, ConceptData conceptData, OntModel ontModel) {
        String conceptIri = (String) conceptMap.get("iri");

        List<ConceptPropertiesModel> properties;
        List<ConceptRelationshipsModel> relationships;

        if (conceptData != null) {
            properties = extractConceptProperties(conceptIri, conceptData);
            relationships = extractConceptRelationships(conceptIri, conceptData);
        } else if (ontModel != null) {
            properties = extractConceptPropertiesFromModel(ontModel, conceptIri);
            relationships = extractConceptRelationshipsFromModel(ontModel, conceptIri);
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
                .exactMatches((List<Map<String, String>>) conceptMap.get(EKVIVALENTNI_POJEM))
                .domain((String) conceptMap.get(DEFINICNI_OBOR))
                .range((String) conceptMap.get(OBOR_HODNOT))
                .broaderClasses((List<String>) conceptMap.get(NADRAZENA_TRIDA))
                .broaderRelations((List<String>) conceptMap.get(NADRAZENY_VZTAH))
                .broaderProperties((List<String>) conceptMap.get(NADRAZENA_VLASTNOST))
                .definingLegalSources((List<String>) conceptMap.get(DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU))
                .relatedLegalSources((List<String>) conceptMap.get(SOUVISEJICI_USTANOVENI_PRAVNIHO_PREDPISU))
                .definingNonLegalSources((List<Map<String, Object>>) conceptMap.get(DEFINUJICI_NELEGISLATIVNI_ZDROJ))
                .relatedNonLegalSources((List<Map<String, Object>>) conceptMap.get(SOUVISEJICI_NELEGISLATIVNI_ZDROJ))
                .sharingMethods((List<String>) conceptMap.get(ZPUSOB_SDILENI_ALT))
                .acquisitionMethod(extractStringFromValue(conceptMap.get(ZPUSOB_ZISKANI_ALT)))
                .contentType(extractStringFromValue(conceptMap.get(TYP_OBSAHU_ALT)))
                .isPpdf((Boolean) conceptMap.get(JE_PPDF))
                .ais(extractStringFromValue(conceptMap.get(AIS)))
                .agenda(extractStringFromValue(conceptMap.get(AGENDA)))
                .privacyProvisions(extractStringFromValue(conceptMap.get(USTANOVENI_NEVEREJNOST)))
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
}
