package com.dia.ismdtoolbackend.exception;

public class ModelProcessingException extends RuntimeException {

    public ModelProcessingException(String message) {
        super(message);
    }

    public ModelProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

    public ModelProcessingException(Throwable cause) {
        super(cause);
    }
}