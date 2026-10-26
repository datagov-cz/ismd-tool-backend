package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.models.*;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.*;
import com.dia.ismdtoolbackend.utility.editor.OntologyEditor;
import com.dia.ismdtoolbackend.utility.URIGenerator;
import com.dia.ismdtoolbackend.utility.UtilityMethods;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.RDF;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Edits a vocabulary, including the graph move and concept IRI rewrite when its IRI changes. */
@Slf4j
@Component
@RequiredArgsConstructor
class OntologyEditOperation {

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final OntologyEditor ontologyEditor;
    private final MetadataTouchService metadataTouchService;
    private final OntologyMetadataMapper ontologyMetadataMapper;
    private final OntologyMetadataEnricher metadataEnricher;
    private final InitialGraphGuard initialGraphGuard;

    OntologyMetadataModel editOntology(Long id, OntologyEditModel ontologyEditModel) {
        if (ontologyEditModel == null) {
            throw new OntologyException("Data pro úpravu slovníku jsou prázdná");
        }
        OntologyMetadataEntity metadataEntity = fetchOntologyMetadata(id);
        String oldOntologyIRI = metadataEntity.getGraphName();
        initialGraphGuard.requireInitialGraphApplied(oldOntologyIRI);
        Model model = fetchOntologyModel(oldOntologyIRI);

        String oldNamespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(oldOntologyIRI);
        OntologyEditor.EditResult editResult = performOntologyEdit(ontologyEditModel, model, oldNamespace, metadataEntity.getGraphName());

        log.info("Ontology edit completed: IRI changed={}", editResult.iriChanged);

        if (editResult.iriChanged) {
            metadataEntity = handleOntologyIRIChange(oldOntologyIRI, editResult.newOntologyIRI, model, metadataEntity);
        } else {
            saveOntologyModel(oldOntologyIRI, model);
        }

        // Both branches bump updatedAt: the IRI-change branch dirties graphName, but an edit that only
        // touches RDF (name, description) leaves every mapped column identical, so the touch is what
        // makes updatedAt move at all.
        metadataTouchService.touchOntology(metadataEntity);

        OntologyMetadataModel resultModel = ontologyMetadataMapper.toDto(metadataEntity);
        metadataEnricher.enrichMetadataFromRDF(resultModel, metadataEntity);
        return resultModel;
    }

    OntologyMetadataEntity fetchOntologyMetadata(Long id) {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findById(id);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("Ontology with ID {} not found", id);
            throw new OntologyException("Slovník s ID " + id + " nebyl nalezen.");
        }
        return ontologyMetadataOpt.get();
    }

    Model fetchOntologyModel(String ontologyIRI) throws OntologyException {
        try {
            Model model = jenaTDB2Repository.fetchGraph(ontologyIRI);
            if (model.isEmpty()) {
                log.error("Ontology model is empty for IRI: {}", ontologyIRI);
                throw new OntologyException("Model slovníku je prázdný nebo nebyl nalezen.");
            }
            return model;
        } catch (Exception e) {
            log.error("Failed to fetch ontology model for IRI: {}. Error: {}", ontologyIRI, e.getMessage());
            throw new OntologyException("Slovník s IRI " + ontologyIRI + " nebyl nalezen v úložišti. " +
                    "Je možné, že došlo k nesrovnalosti mezi databází a RDF úložištěm. " +
                    "Zkontrolujte, zda slovník existuje v Fuseki.");
        }
    }

    OntologyEditor.EditResult performOntologyEdit(OntologyEditModel editModel, Model model, String oldNamespace, String iri) {
        return ontologyEditor.editOntology(editModel, model, oldNamespace, iri);
    }

    OntologyMetadataEntity handleOntologyIRIChange(String oldOntologyIRI, String newOntologyIRI,
                                                           Model model, OntologyMetadataEntity metadataEntity)
            throws OntologyException {
        initialGraphGuard.requireInitialGraphApplied(newOntologyIRI);
        log.info("Starting ontology IRI change: {} -> {}", oldOntologyIRI, newOntologyIRI);

        try {
            saveOntologyModel(newOntologyIRI, model);
            log.info("Successfully saved ontology to new graph: {}", newOntologyIRI);
        } catch (Exception e) {
            log.error("Failed to save new graph to Fuseki: {}", e.getMessage());
            throw new OntologyException("Nepodařilo se uložit nový graf do Fuseki: " + e.getMessage());
        }

        try {
            String oldNamespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(oldOntologyIRI);

            updateConceptMetadataIRIs(metadataEntity.getId(), newOntologyIRI, oldNamespace);
            log.info("Successfully updated concept metadata in SQL");

            OntologyMetadataEntity updatedEntity = updateOntologyMetadata(metadataEntity, newOntologyIRI);
            log.info("Successfully updated ontology metadata in SQL");

            try {
                deleteOntologyGraph(oldOntologyIRI);
                log.info("Successfully deleted old graph: {}", oldOntologyIRI);
            } catch (Exception e) {
                log.warn("Failed to delete old graph {}, but operation succeeded. Manual cleanup may be needed: {}",
                        oldOntologyIRI, e.getMessage());
            }

            return updatedEntity;
        } catch (Exception e) {
            log.error("Failed to update SQL, rolling back. Attempting to clean up new graph: {}", newOntologyIRI);

            try {
                deleteOntologyGraph(newOntologyIRI);
                log.info("Successfully cleaned up new graph after SQL failure: {}", newOntologyIRI);
            } catch (Exception cleanupException) {
                log.error("Failed to cleanup new graph {} after SQL failure. Manual cleanup required: {}",
                        newOntologyIRI, cleanupException.getMessage());
            }

            throw new OntologyException("Nepodařilo se aktualizovat databázi: " + e.getMessage());
        }
    }

    void updateConceptMetadataIRIs(Long ontologyMetadataId, String newGraphName,
                                           String oldNamespace) {
        List<ConceptMetadataEntity> concepts = conceptMetadataRepository.findByOntologyMetadataId(ontologyMetadataId);

        if (concepts.isEmpty()) {
            log.info("No concepts found for ontology ID {}, skipping concept metadata updates", ontologyMetadataId);
            return;
        }

        log.info("Updating {} concept metadata entries for ontology namespace change", concepts.size());

        URIGenerator uriGenerator = new URIGenerator();
        uriGenerator.setEffectiveNamespace(newGraphName);

        int updatedIRICount = 0;
        int updatedGraphNameOnlyCount = 0;

        for (ConceptMetadataEntity concept : concepts) {
            String oldConceptIRI = concept.getConceptIri();
            String conceptName = concept.getConceptName();

            if (oldConceptIRI != null && conceptName != null && oldConceptIRI.startsWith(oldNamespace)) {
                String newConceptIRI = uriGenerator.generateConceptURI(conceptName, null);
                concept.setConceptIri(newConceptIRI);
                concept.setGraphName(newGraphName);
                log.debug("Updated concept IRI and graphName: {} -> {}", oldConceptIRI, newConceptIRI);
                updatedIRICount++;
            } else {
                concept.setGraphName(newGraphName);
                log.debug("Updated graphName only for concept with custom identifier: {}", oldConceptIRI);
                updatedGraphNameOnlyCount++;
            }
        }

        conceptMetadataRepository.saveAll(concepts);
        log.info("Successfully updated {} concept IRIs and {} graphNames for concepts with custom identifiers",
                 updatedIRICount, updatedGraphNameOnlyCount);
    }

    void saveOntologyModel(String ontologyIRI, Model model) {
        try {
            jenaTDB2Repository.saveOntologyModel(ontologyIRI, model);
            log.info("Saved/updated ontology model for graph: {}", ontologyIRI);
        } catch (Exception e) {
            log.error("Failed to save ontology model: {}", e.getMessage());
            throw new OntologyException("Nepodařilo se uložit změny slovníku: " + e.getMessage());
        }
    }

    void deleteOntologyGraph(String ontologyIRI) {
        jenaTDB2Repository.deleteGraph(ontologyIRI);
    }

    OntologyMetadataEntity updateOntologyMetadata(OntologyMetadataEntity metadataEntity, String newGraphName) {
        metadataEntity.setGraphName(newGraphName);
        OntologyMetadataEntity updatedEntity = ontologyMetadataRepository.save(metadataEntity);
        log.info("Updated metadata with new graph name: {} (slug unchanged: {})", newGraphName, metadataEntity.getSlug());
        return updatedEntity;
    }
}
