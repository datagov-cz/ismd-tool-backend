package com.dia.ismdtoolbackend.exception;

/** Another publication run holds the lock. Nothing is built. */
public class PublicationInProgressException extends RuntimeException {

    public PublicationInProgressException(String message) {
        super(message);
    }
}
