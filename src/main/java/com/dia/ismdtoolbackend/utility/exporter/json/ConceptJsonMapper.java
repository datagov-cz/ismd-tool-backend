package com.dia.ismdtoolbackend.utility.exporter.json;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.SKOS;

import java.util.*;

import com.dia.ismdtoolbackend.exception.ConflictingConceptTypeException;
import com.dia.ismdtoolbackend.exception.ModelProcessingException;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;

/** Maps the concepts of an RDF model to their JSON / JSON-LD objects, one focused mapper per characteristic. */
@Slf4j
public class ConceptJsonMapper {

    private final ConceptTypeJsonMapper types = new ConceptTypeJsonMapper();
    private final ConceptLabelJsonMapper labels = new ConceptLabelJsonMapper();
    private final ConceptSourceJsonMapper sources = new ConceptSourceJsonMapper();
    private final ConceptRelationJsonMapper relations = new ConceptRelationJsonMapper();
    private final ConceptGovernanceJsonMapper governance = new ConceptGovernanceJsonMapper();
    private final ConceptPrivacyJsonMapper privacy = new ConceptPrivacyJsonMapper();
    private final ConceptCodeListJsonMapper codeLists = new ConceptCodeListJsonMapper();

    public ConceptData processAllConcepts(OntModel ontModel, ModelStructure structure) {
        if (ontModel == null) {
            throw new ModelProcessingException("OntModel cannot be null");
        }
        if (structure == null) {
            throw new ModelProcessingException("ModelStructure cannot be null");
        }

        log.debug("Processing concepts for JSON export");

        try {
            Set<Resource> conceptTypes = types.getConceptTypes(ontModel);
            List<Map<String, Object>> concepts = new ArrayList<>();
            int failedConceptCount = 0;

            for (Resource resource : structure.getResourceMap().values()) {
                if (types.isConceptResource(resource, conceptTypes)) {
                    failedConceptCount += processSingleConcept(resource, ontModel, structure, concepts);
                }
            }

            if (failedConceptCount > 0) {
                log.warn("Failed to process {} concepts out of {} total resources",
                        failedConceptCount, structure.getResourceMap().size());
            }

            log.debug("Successfully processed {} concepts", concepts.size());
            return ConceptData.builder()
                    .concepts(concepts)
                    .totalConceptCount(concepts.size())
                    .build();

        } catch (Exception e) {
            log.error("Critical error during concept processing: {}", e.getMessage(), e);
            throw new ModelProcessingException("Failed to process concepts: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> processConceptByIri(OntModel ontModel, ModelStructure structure, String conceptIri) {
        if (ontModel == null) {
            throw new ModelProcessingException("OntModel cannot be null");
        }
        if (structure == null) {
            throw new ModelProcessingException("ModelStructure cannot be null");
        }
        if (conceptIri == null || conceptIri.isEmpty()) {
            throw new ModelProcessingException("Concept IRI cannot be null or empty");
        }

        log.debug("Processing single concept with IRI: {}", conceptIri);

        try {
            Resource concept = ontModel.getResource(conceptIri);
            if (concept == null) {
                throw new ModelProcessingException("Concept not found: " + conceptIri);
            }

            Set<Resource> conceptTypes = types.getConceptTypes(ontModel);
            if (!types.isConceptResource(concept, conceptTypes)) {
                throw new ModelProcessingException("Resource is not a valid concept: " + conceptIri);
            }

            return createConceptObject(concept, ontModel, structure);

        } catch (Exception e) {
            log.error("Failed to process concept {}: {}", conceptIri, e.getMessage(), e);
            throw new ModelProcessingException("Failed to process concept: " + e.getMessage(), e);
        }
    }

    private int processSingleConcept(Resource resource, OntModel ontModel, ModelStructure structure, List<Map<String, Object>> concepts) {
        try {
            Map<String, Object> conceptObject = createConceptObject(resource, ontModel, structure);
            concepts.add(conceptObject);
            return 0;
        } catch (ConflictingConceptTypeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to process concept: {} - {}", resource.getURI(), e.getMessage(), e);
            return 1;
        }
    }

    private Map<String, Object> createConceptObject(Resource concept, OntModel ontModel, ModelStructure structure) {
        Map<String, Object> conceptObj = new LinkedHashMap<>();

        conceptObj.put("iri", concept.getURI());
        conceptObj.put("typ", types.getConceptTypes(concept, ontModel));

        labels.addMultilingualProperty(concept, SKOS.prefLabel, NAZEV, conceptObj);
        labels.addAlternativeNames(concept, conceptObj);

        Property definitionProperty = ontModel.createProperty(SKOS_NS + "definition");
        labels.addMultilingualProperty(concept, definitionProperty, DEFINICE, conceptObj);

        labels.addDescriptionProperty(concept, conceptObj, ontModel);

        Property identifierProperty = ontModel.createProperty(DCT_NS + "identifier");
        RdfJsonValues.addResourceArrayProperty(concept, identifierProperty, IDENTIFIKATOR, conceptObj);

        relations.addExactMatchProperty(concept, conceptObj, ontModel);

        sources.addSourceProperties(concept, conceptObj, ontModel, structure.getEffectiveNamespace());

        relations.addDomainAndRange(concept, conceptObj);

        relations.addHierarchicalRelationships(concept, conceptObj, ontModel);

        governance.addGovernanceProperties(concept, conceptObj, ontModel);

        governance.addMetadataProperties(concept, conceptObj, ontModel);

        privacy.addUstanoveniProperty(concept, conceptObj, ontModel);

        codeLists.addCodeListDatasetProperty(concept, conceptObj, ontModel);

        return conceptObj;
    }
}
