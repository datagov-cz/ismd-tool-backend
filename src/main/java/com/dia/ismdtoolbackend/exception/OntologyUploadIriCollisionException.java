package com.dia.ismdtoolbackend.exception;

/**
 * The uploaded file holds two resources whose IRIs differ only by a trailing slash, so
 * correcting one would merge it into the other. Nothing is persisted.
 */
public class OntologyUploadIriCollisionException extends OntologyUploadException {

    public OntologyUploadIriCollisionException(String message) {
        super(message);
    }
}
