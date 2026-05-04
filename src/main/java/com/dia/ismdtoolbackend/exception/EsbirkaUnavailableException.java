package com.dia.ismdtoolbackend.exception;

public class EsbirkaUnavailableException extends RuntimeException {
    public EsbirkaUnavailableException(String message) {
        super(message);
    }

    public EsbirkaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
