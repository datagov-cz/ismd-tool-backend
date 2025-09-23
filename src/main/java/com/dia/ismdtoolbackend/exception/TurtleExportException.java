package com.dia.ismdtoolbackend.exception;

public class TurtleExportException extends RuntimeException {

    public TurtleExportException(String message) {
        super(message);
    }

    public TurtleExportException(String message, Throwable cause) {
        super(message, cause);
    }

    public TurtleExportException(Throwable cause) {
        super(cause);
    }
}