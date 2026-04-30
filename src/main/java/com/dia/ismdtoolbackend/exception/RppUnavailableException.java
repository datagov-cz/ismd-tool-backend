package com.dia.ismdtoolbackend.exception;

public class RppUnavailableException extends RuntimeException {
    public RppUnavailableException(String message) {
        super(message);
    }

    public RppUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
