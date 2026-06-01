package com.dia.ismdtoolbackend.controller.dto;

import com.dia.validation.ValidationReport;
import lombok.Data;

@Data
public class CatalogRecordRequestDto {
    private ValidationReport validationReport;
    private String ttlContent;
}
