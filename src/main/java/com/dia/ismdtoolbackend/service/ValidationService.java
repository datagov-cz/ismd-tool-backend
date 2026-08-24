package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.exception.ValidationException;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.validation.ValidationReport;
import com.dia.validation.ValidationReportDto;

public interface ValidationService {
    void saveValidationReport(ValidationReport validationReport, OntologyMetadataModel ontologyMetadataModel, String userId) throws ValidationException;
    ValidationReportDto getValidationReport(OntologyMetadataModel ontologyMetadataModel) throws ValidationException;

    /**
     * The last stored report for an ontology, never {@code null}: an ontology that was never
     * validated yields an empty report rather than an absent one. Never validated is a state,
     * not an error — the ontology's {@code lastValidationStatus} already tells the caller why.
     * Use this on read paths; {@link #getValidationReport} keeps its nullable contract for
     * callers that gate on a report existing at all.
     */
    ValidationReportDto getValidationReportOrEmpty(OntologyMetadataModel ontologyMetadataModel) throws ValidationException;
}
