package com.dia.ismdtoolbackend.exception;

/** The uploaded file exceeds the configured size limit. Nothing is persisted. */
public class OntologyUploadFileTooLargeException extends OntologyUploadException {

    /** Stable error code the FE branches on, rather than the localized message. */
    public static final String ERROR_CODE = "UPLOAD_FILE_TOO_LARGE";

    public OntologyUploadFileTooLargeException(String message) {
        super(message);
    }

    public OntologyUploadFileTooLargeException(String message, Throwable cause) {
        super(message, cause);
    }
}
