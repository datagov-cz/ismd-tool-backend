package com.dia.ismdtoolbackend.exception;

public class NkdEndpointException extends RuntimeException {
    public NkdEndpointException(String message) {
        super(message);
    }

    public NkdEndpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
