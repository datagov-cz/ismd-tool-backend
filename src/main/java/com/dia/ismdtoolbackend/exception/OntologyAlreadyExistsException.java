package com.dia.ismdtoolbackend.exception;

import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import lombok.Getter;

@Getter
public class OntologyAlreadyExistsException extends RuntimeException {
    private final OntologyMetadataModel existingMetadata;

    public OntologyAlreadyExistsException(String message, OntologyMetadataModel existingMetadata) {
        super(message);
        this.existingMetadata = existingMetadata;
    }
}