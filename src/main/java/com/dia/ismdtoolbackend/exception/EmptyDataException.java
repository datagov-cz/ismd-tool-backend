package com.dia.ismdtoolbackend.exception;

public class EmptyDataException extends RuntimeException {
    public EmptyDataException(String message) {
        super(message);
    }

    public EmptyDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
