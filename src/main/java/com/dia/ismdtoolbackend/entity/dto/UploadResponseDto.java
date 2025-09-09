package com.dia.ismdtoolbackend.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class UploadResponseDto {
    private OntologyMetadataDto ontologyMetadata;
    private String message;
}
