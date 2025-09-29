package com.dia.ismdtoolbackend.exception;

public class JenaTDB2Exception extends RuntimeException {
    public JenaTDB2Exception(String message, Exception e) {
        super(message, e);
    }
}
