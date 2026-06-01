package com.dia.ismdtoolbackend.exception;

public class OntologyAnalysisException extends RuntimeException {
    public OntologyAnalysisException(Exception e) {
        super(e);
    }

    public OntologyAnalysisException(Exception e, String message) {
        super(message, e);
    }
}
