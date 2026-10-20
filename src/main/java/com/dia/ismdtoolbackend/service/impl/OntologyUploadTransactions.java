package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.enums.OntologyValidationStatus;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.client.validation.ValidationReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The upload steps that each run in their own Postgres transaction. */
@Component
@Transactional
@RequiredArgsConstructor
@Slf4j
class OntologyUploadTransactions {

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ValidationReportRepository validationReportRepository;
    private final UploadMetadataWriter metadataWriter;

    /** Writes the vocabulary's metadata row and the rows of its concepts. */
    public OntologyMetadataModel saveMetadata(OntModel model, String graphName, String userId,
                                              List<String> publishedConceptIris) {
        OntologyMetadataModel metadata = metadataWriter.createOntologyMetadataEntity(graphName, userId);
        boolean ontologyPublished = metadataWriter.extractAndSaveConceptMetadata(
                model, graphName, userId, metadata.getId(), publishedConceptIris);
        // extractAndSaveConceptMetadata flips is_published on a re-fetched entity; mirror it
        // onto the returned model so the upload response matches the persisted row.
        metadata.setIsPublished(ontologyPublished);
        return metadata;
    }

    /**
     * Atomically persists the validation outcome: deletes any prior report, saves the new one (if
     * the validator answered), and marks {@code last_validation_status}. {@code @Transactional} so
     * the whole sequence commits or rolls back together — never a saved report without a status, or
     * a status without its report. {@code report == null} means the validator was unavailable →
     * {@code SKIPPED_UNAVAILABLE} (the ontology is ingested regardless).
     */
    public void saveValidationOutcome(String iri, ValidationReport report) {
        Optional<OntologyMetadataEntity> ontologyOpt = ontologyMetadataRepository.findByGraphName(iri);
        if (ontologyOpt.isEmpty()) {
            log.warn("Ontology metadata not found for graph name: {}", iri);
            return;
        }
        OntologyMetadataEntity ontology = ontologyOpt.get();

        Optional<ValidationReportEntity> validationReportOpt = validationReportRepository.findByOntologyMetadataId(ontology.getId());
        validationReportOpt.ifPresent(validationReportRepository::delete);

        if (report != null) {
            ValidationReportEntity validationReportEntity = new ValidationReportEntity();
            validationReportEntity.setId(report.getId());
            validationReportEntity.setTimestamp(report.getTimestamp());
            validationReportEntity.setOntologyMetadataId(ontology.getId());
            validationReportEntity.setGetOntologyIri(ontology.getGraphName());
            String validationResults = validationReportEntity.convertResultsToJson(report.getResults());
            validationReportEntity.setResultsJson(validationResults);
            validationReportRepository.save(validationReportEntity);
            markValidationStatus(ontology, OntologyValidationStatus.VALIDATED);
        } else {
            markValidationStatus(ontology, OntologyValidationStatus.SKIPPED_UNAVAILABLE);
        }
    }

    private void markValidationStatus(OntologyMetadataEntity ontology, OntologyValidationStatus status) {
        ontology.setLastValidationStatus(status);
        ontology.setLastValidationAt(Instant.now());
        ontologyMetadataRepository.save(ontology);
    }
}
