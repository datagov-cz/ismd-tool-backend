package com.dia.ismdtoolbackend.exception;

import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import lombok.Getter;

@Getter
public class OntologyAlreadyExistsException extends RuntimeException {

    /** Stable error code the FE branches on, rather than the localized message. */
    public static final String ERROR_CODE = "ONTOLOGY_ALREADY_EXISTS";

    private final OntologyMetadataModel existingMetadata;

    public OntologyAlreadyExistsException(String message, OntologyMetadataModel existingMetadata) {
        super(message);
        this.existingMetadata = existingMetadata;
    }
}
