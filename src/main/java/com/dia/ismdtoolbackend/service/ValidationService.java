package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.exception.ValidationException;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.validation.ValidationReport;
import com.dia.validation.ValidationReportDto;

public interface ValidationService {
    void saveValidationReport(ValidationReport validationReport, OntologyMetadataModel ontologyMetadataModel) throws ValidationException;
    ValidationReportDto getValidationReport(OntologyMetadataModel ontologyMetadataModel) throws ValidationException;
}
