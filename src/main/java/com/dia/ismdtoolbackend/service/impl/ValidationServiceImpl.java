package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.enums.OntologyValidationStatus;
import com.dia.ismdtoolbackend.exception.ValidationException;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.ValidationService;
import com.dia.validation.ValidationReport;
import com.dia.validation.ValidationReportDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ValidationServiceImpl implements ValidationService {

    private final ValidationReportRepository validationReportRepository;
    private final OntologyMetadataRepository ontologyMetadataRepository;

    @Override
    @Transactional
    public void saveValidationReport(ValidationReport validationReport, OntologyMetadataModel ontologyMetadataModel, String userId) throws ValidationException {
        Optional<ValidationReportEntity> validationReportEntityOpt = validationReportRepository.findByOntologyMetadataId(ontologyMetadataModel.getId());
        validationReportEntityOpt.ifPresent(validationReportRepository::delete);

        try {
            ValidationReportEntity validationReportEntity = new ValidationReportEntity();
            validationReportEntity.setId(validationReport.getId());
            validationReportEntity.setUserId(userId);
            validationReportEntity.setTimestamp(validationReport.getTimestamp());
            validationReportEntity.setOntologyMetadataId(ontologyMetadataModel.getId());
            validationReportEntity.setGetOntologyIri(ontologyMetadataModel.getGraphName());
            String validationResults = validationReportEntity.convertResultsToJson(validationReport.getResults());
            validationReportEntity.setResultsJson(validationResults);
            validationReportRepository.save(validationReportEntity);

            // A successful save means the validator evaluated the ontology — clear any prior
            // SKIPPED_UNAVAILABLE so a manual re-validation un-flags it.
            markValidated(ontologyMetadataModel.getId());
        } catch (Exception e) {
            throw new ValidationException("Během ukládání zprávy z kontroly došlo k chybě", e);
        }
    }

    private void markValidated(Long ontologyMetadataId) {
        ontologyMetadataRepository.findById(ontologyMetadataId).ifPresent(ontology -> {
            ontology.setLastValidationStatus(OntologyValidationStatus.VALIDATED);
            ontology.setLastValidationAt(Instant.now());
            ontologyMetadataRepository.save(ontology);
        });
    }

    @Override
    public ValidationReportDto getValidationReport(OntologyMetadataModel ontologyMetadataModel) throws ValidationException {
        Optional<ValidationReportEntity> validationReportEntityOpt = validationReportRepository.findByOntologyMetadataId(ontologyMetadataModel.getId());
        return validationReportEntityOpt.map(validationReportEntity -> new ValidationReportDto(
                validationReportEntity.getResults(),
                ontologyMetadataModel.getGraphName(),
                validationReportEntity.getTimestamp()))
                .orElse(null);
    }

    @Override
    public ValidationReportDto getValidationReportOrEmpty(OntologyMetadataModel ontologyMetadataModel) throws ValidationException {
        ValidationReportDto report = getValidationReport(ontologyMetadataModel);
        return report != null
                ? report
                : new ValidationReportDto(List.of(), ontologyMetadataModel.getGraphName(), null);
    }
}
