package com.dia.ismdtoolbackend.exception;

public class JsonExportException extends RuntimeException {

    public JsonExportException(String message) {
        super(message);
    }

    public JsonExportException(String message, Throwable cause) {
        super(message, cause);
    }

    public JsonExportException(Throwable cause) {
        super(cause);
    }
}