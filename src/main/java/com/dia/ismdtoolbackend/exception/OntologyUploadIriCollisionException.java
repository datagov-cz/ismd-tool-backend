package com.dia.ismdtoolbackend.exception;

/**
 * The uploaded file holds two resources whose IRIs differ only by a trailing slash, so
 * correcting one would merge it into the other. Nothing is persisted.
 */
public class OntologyUploadIriCollisionException extends OntologyUploadException {

    /** Stable error code the FE branches on, rather than the localized message. */
    public static final String ERROR_CODE = "UPLOAD_IRI_TRAILING_SLASH_COLLISION";

    public OntologyUploadIriCollisionException(String message) {
        super(message);
    }
}
