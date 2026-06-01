package com.dia.ismdtoolbackend.exception;

public class UnsupportedRdfFormatException extends RuntimeException {
    public UnsupportedRdfFormatException(String message) {
        super(message);
    }

    public UnsupportedRdfFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
