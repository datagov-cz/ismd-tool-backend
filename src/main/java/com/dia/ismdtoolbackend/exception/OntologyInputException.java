package com.dia.ismdtoolbackend.exception;

/** Invalid client input for a vocabulary or concept. The message is written for the client. */
public class OntologyInputException extends RuntimeException {
    public OntologyInputException(String message) {
        super(message);
    }
}
