package com.dia.ismdtoolbackend.exception;

public class NkdResourceNotFoundException extends RuntimeException {
    public NkdResourceNotFoundException(String message) {
        super(message);
    }

    public NkdResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
