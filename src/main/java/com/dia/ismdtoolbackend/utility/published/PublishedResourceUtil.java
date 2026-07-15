package com.dia.ismdtoolbackend.utility.published;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.NkdConceptRefDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.PublishedOntologyDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.service.impl.ConceptDeviationComparator;
import com.dia.ismdtoolbackend.service.impl.OntologyDeviationComparator;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.shared.Lock;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@RequiredArgsConstructor
@Slf4j
public class PublishedResourceUtil {

    private final NkdSparqlClient nkdSparqlClient;
    private final OntologyDetailExtractor detailExtractor;
    private final OntologyDeviationComparator ontologyDeviationComparator;
    private final ConceptDeviationComparator conceptDeviationComparator;

    @Value("${nkd.deviation.parallelism:8}")
    private int deviationParallelism;

    private static final String POJEM_GENERIC = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/pojem";

    public PublishedOntologyDeviationModel checkOntologyDeviation(Model processedModel, OntologyMetadataModel ontologyMetadata) {
        if (Boolean.FALSE.equals(ontologyMetadata.getIsPublished())) {
            log.debug("Ontology {} is not published, skipping deviation check", ontologyMetadata.getGraphName());
            return null;
        }

        String ontologyIri = ontologyMetadata.getGraphName();
        log.info("Checking ontology deviation for: {}", ontologyIri);

        try {
            OntologyDetailModel localOntology = detailExtractor.extractOntologyDetail(processedModel);

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

        // Per-concept deviation checks each fire a CONSTRUCT to NKD; sequential
        // execution turned the detail endpoint into N×roundtrip latency. Fan out
        // across a small pool to bound NKD load while collapsing wall time.
        int parallelism = Math.max(1, Math.min(deviationParallelism, publishedConcepts.size()));
        log.info("Checking deviations for {} published concepts (parallelism={})",
                publishedConcepts.size(), parallelism);

        Map<String, PublishedConceptDeviationModel> deviations = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<CompletableFuture<Void>> futures = publishedConcepts.stream()
                    .map(conceptEntity -> CompletableFuture.runAsync(() -> {
                        String conceptIri = conceptEntity.getConceptIri();
                        try {
                            PublishedConceptDeviationModel deviation =
                                    checkSingleConceptDeviation(processedModel, conceptIri);
                            if (deviation != null) {
                                deviations.put(conceptIri, deviation);
                            }
                        } catch (Exception e) {
                            log.error("Error checking concept deviation for {}: {}", conceptIri, e.getMessage(), e);
                            deviations.put(conceptIri, createErrorConceptDeviation(
                                    PublishedConceptDeviationModel.DeviationStatus.QUERY_ERROR,
                                    "Error checking concept: " + e.getMessage(),
                                    conceptIri
                            ));
                        }
                    }, executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }

        log.info("Completed deviation checks for {} concepts", deviations.size());
        return deviations;
    }

    private PublishedConceptDeviationModel checkSingleConceptDeviation(Model processedModel, String conceptIri) {
        try {
            // Shared Jena Model is read concurrently by the deviation fan-out;
            // enterCriticalSection(READ) is the documented way to make in-memory
            // reads safe under concurrency.
            OntologyDetailModel.ConceptDetailModel localConcept;
            processedModel.enterCriticalSection(Lock.READ);
            try {
                localConcept = detailExtractor.extractConceptDetail(processedModel, conceptIri);
            } finally {
                processedModel.leaveCriticalSection();
            }

            if (localConcept == null) {
                log.error("Local concept detail not found for IRI: {}", conceptIri);
                return createErrorConceptDeviation(
                        PublishedConceptDeviationModel.DeviationStatus.QUERY_ERROR,
                        "Local concept detail not available",
                        conceptIri
                );
            }

            Optional<OntologyDetailModel.ConceptDetailModel> publishedConceptOpt =
                    nkdSparqlClient.fetchPublishedConcept(conceptIri);

            if (publishedConceptOpt.isEmpty()) {
                log.warn("Published concept not found in NKD: {}", conceptIri);
                return createErrorConceptDeviation(
                        PublishedConceptDeviationModel.DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD,
                        "Concept not found in NKD SPARQL endpoint",
                        conceptIri
                );
            }

            OntologyDetailModel.ConceptDetailModel publishedConcept = publishedConceptOpt.get();
            // WORKING_COPY: this concept's own IRI is the NKD twin it is compared against.
            PublishedConceptDeviationModel deviation = conceptDeviationComparator.compareConceptDetails(
                    localConcept, publishedConcept, SnapshotOrigin.WORKING_COPY, conceptIri);

            log.debug("Concept deviation check completed for {}: status={}", conceptIri, deviation.getStatus());
            return deviation;

        } catch (Exception e) {
            log.error("Error checking published concept deviation for {}: {}", conceptIri, e.getMessage(), e);
            return createErrorConceptDeviation(
                    PublishedConceptDeviationModel.DeviationStatus.ENDPOINT_UNAVAILABLE,
                    "NKD SPARQL endpoint unavailable: " + e.getMessage(),
                    conceptIri
            );
        }
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
            PublishedConceptDeviationModel.DeviationStatus status,
            String errorMessage,
            String conceptIri) {
        return PublishedConceptDeviationModel.builder()
                .status(status)
                .errorMessage(errorMessage)
                .origin(SnapshotOrigin.WORKING_COPY)
                .source(NkdConceptRefDto.builder().iri(conceptIri).build())
                .build();
    }
}