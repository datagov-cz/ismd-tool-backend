package com.dia.ismdtoolbackend.utility.published;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.NkdConceptRefDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.PublishedOntologyDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.service.impl.OntologyDeviationComparator;
import com.dia.ismdtoolbackend.service.WorkingCopyDeviationService;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class PublishedResourceUtil {

    private final NkdSparqlClient nkdSparqlClient;
    private final OntologyDetailExtractor detailExtractor;
    private final OntologyDeviationComparator ontologyDeviationComparator;
    private final WorkingCopyDeviationService workingCopyDeviationService;

    private static final String POJEM_GENERIC = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/pojem";

    /**
     * @param localOntology the caller's already-extracted detail for this graph — the local side of the
     *                      comparison. Extraction walks every concept and resolves each member's slug, so
     *                      re-deriving it here would duplicate that whole pass.
     */
    public PublishedOntologyDeviationModel checkOntologyDeviation(OntologyDetailModel localOntology,
                                                                  OntologyMetadataModel ontologyMetadata) {
        if (Boolean.FALSE.equals(ontologyMetadata.getIsPublished())) {
            log.debug("Ontology {} is not published, skipping deviation check", ontologyMetadata.getGraphName());
            return null;
        }

        String ontologyIri = ontologyMetadata.getGraphName();
        log.info("Checking ontology deviation for: {}", ontologyIri);

        try {
            if (localOntology == null) {
                log.error("Local ontology detail not found for IRI: {}", ontologyIri);
                return createErrorOntologyDeviation(
                        PublishedConceptDeviationModel.DeviationStatus.QUERY_ERROR,
                        "Local ontology detail not available"
                );
            }

            Optional<OntologyDetailModel> publishedOntologyOpt =
                    nkdSparqlClient.fetchPublishedOntology(ontologyIri);

            if (publishedOntologyOpt.isEmpty()) {
                log.warn("Published ontology not found in NKD: {}", ontologyIri);
                return createErrorOntologyDeviation(
                        PublishedConceptDeviationModel.DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD,
                        "Ontology not found in NKD SPARQL endpoint"
                );
            }

            OntologyDetailModel publishedOntology = publishedOntologyOpt.get();
            PublishedOntologyDeviationModel deviation = ontologyDeviationComparator.compareOntologyDetails(localOntology, publishedOntology);

            log.info("Ontology deviation check completed for {}: status={}", ontologyIri, deviation.getStatus());
            return deviation;

        } catch (Exception e) {
            log.error("Error checking published ontology deviation for {}: {}", ontologyIri, e.getMessage(), e);
            return createErrorOntologyDeviation(
                    PublishedConceptDeviationModel.DeviationStatus.ENDPOINT_UNAVAILABLE,
                    "NKD SPARQL endpoint unavailable: " + e.getMessage()
            );
        }
    }

    public Map<String, PublishedConceptDeviationModel> checkConceptsDeviation(Model processedModel, List<ConceptMetadataEntity> conceptMetadataEntities) {
        List<ConceptMetadataEntity> publishedConcepts = conceptMetadataEntities.stream()
                .filter(concept -> Boolean.TRUE.equals(concept.getIsPublished()))
                .toList();

        if (publishedConcepts.isEmpty()) {
            log.info("No published concepts found, skipping deviation checks");
            return new HashMap<>();
        }

        List<String> conceptIris = publishedConcepts.stream()
                .map(ConceptMetadataEntity::getConceptIri)
                .toList();
        log.info("Checking deviations for {} published concepts", conceptIris.size());

        // Single source of truth: the same computation concept detail uses, so the two surfaces can
        // never disagree. The bulk entry point reads every local projection off the processedModel this
        // request already transformed — the per-IRI path would re-fetch and re-transform the whole
        // ontology graph once per concept, since a concept's graphName IS the ontology graph.
        Map<String, PublishedConceptDeviationModel> deviations = new HashMap<>();
        try {
            deviations.putAll(workingCopyDeviationService.deviationForAll(processedModel, conceptIris));
        } catch (Exception e) {
            // Bulk local extraction failed as a unit; degrade to a per-concept error rather than
            // failing the whole detail response.
            log.error("Bulk deviation check failed for {} concepts: {}", conceptIris.size(), e.getMessage(), e);
            for (String conceptIri : conceptIris) {
                deviations.put(conceptIri, createErrorConceptDeviation(
                        "Error checking concept: " + e.getMessage(), conceptIri));
            }
        }

        log.info("Completed deviation checks for {} concepts", deviations.size());
        return deviations;
    }

    private PublishedOntologyDeviationModel createErrorOntologyDeviation(
            PublishedConceptDeviationModel.DeviationStatus status,
            String errorMessage) {
        return PublishedOntologyDeviationModel.builder()
                .status(status)
                .errorMessage(errorMessage)
                .build();
    }

    public List<String> checkPublishedResourcesInNKD(OntModel model) {
        List<String> resourceIris = new ArrayList<>();

        ResIterator ontologyIterator = model.listResourcesWithProperty(RDF.type, OWL2.Ontology);
        if (ontologyIterator.hasNext()) {
            Resource ontologyResource = ontologyIterator.next();
            if (ontologyResource.isURIResource()) {
                String ontologyIri = ontologyResource.getURI();
                resourceIris.add(ontologyIri);
                log.debug("Added ontology IRI to NKD verification list: {}", ontologyIri);
            }
        }

        Resource pojemResource = model.createResource(POJEM_GENERIC);
        ResIterator conceptIterator = model.listResourcesWithProperty(RDF.type, pojemResource);

        while (conceptIterator.hasNext()) {
            Resource conceptResource = conceptIterator.next();

            if (conceptResource.isURIResource()) {
                String conceptIri = conceptResource.getURI();
                resourceIris.add(conceptIri);
            }
        }

        log.debug("Extracted {} resource IRIs from model for NKD verification", resourceIris.size());

        if (resourceIris.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> publishedIris = nkdSparqlClient.getPublishedResourcesList(resourceIris);
        log.info("Found {} published resources in NKD out of {} total resources",
                publishedIris.size(), resourceIris.size());

        return publishedIris;
    }

    /**
     * An error envelope still carries {@code origin}/{@code source} — the comparison failed, but this is
     * known to be a working copy and the twin's IRI is known, and the FE needs both to render the block.
     * No label: it lives on the NKD concept we could not fetch.
     */
    private PublishedConceptDeviationModel createErrorConceptDeviation(
            String errorMessage,
            String conceptIri) {
        return PublishedConceptDeviationModel.builder()
                .status(PublishedConceptDeviationModel.DeviationStatus.QUERY_ERROR)
                .errorMessage(errorMessage)
                .origin(SnapshotOrigin.WORKING_COPY)
                .source(NkdConceptRefDto.builder().iri(conceptIri).build())
                .build();
    }
}