package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.validation.ValidationReportDto;
import lombok.Data;

@Data
public class CatalogRequestDto {
    private OntologyMetadataModel ontologyMetadata;
    private ValidationReportDto validationReport;
}
