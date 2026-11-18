package com.dia.ismdtoolbackend.exception;

/** A stored publication file whose content is past the retention; its metadata is still kept. */
public class PublicationFilePrunedException extends RuntimeException {

    public PublicationFilePrunedException(String message) {
        super(message);
    }
}
