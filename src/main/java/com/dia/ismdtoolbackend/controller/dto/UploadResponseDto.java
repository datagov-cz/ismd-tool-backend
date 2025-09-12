package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.entity.models.OntologyMetadataModel;
import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class UploadResponseDto {
    private OntologyMetadataModel ontologyMetadata;
    private String message;
}
