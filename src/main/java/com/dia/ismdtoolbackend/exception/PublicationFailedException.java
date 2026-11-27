package com.dia.ismdtoolbackend.exception;

/** A publication run that committed nothing; the previous publication stays current. */
public class PublicationFailedException extends RuntimeException {

    public PublicationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
