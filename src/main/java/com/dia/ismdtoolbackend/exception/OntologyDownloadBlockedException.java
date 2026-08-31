package com.dia.ismdtoolbackend.exception;

import com.dia.ismdtoolbackend.controller.dto.ValidationErrorSummaryDto;

import java.util.List;

/**
 * Thrown on download when the stored validation report holds ERROR-severity results and
 * {@code validation.rules.enable-ontology-violation-download} is false. Carries the data needed to
 * render the {@code ONTOLOGY_DOWNLOAD_BLOCKED_BY_VALIDATION} (HTTP 400) response so the user learns
 * why the file was refused instead of receiving an empty body.
 */
public class OntologyDownloadBlockedException extends RuntimeException {

    /** Stable error code the FE branches on (not the localized message). */
    public static final String ERROR_CODE = "ONTOLOGY_DOWNLOAD_BLOCKED_BY_VALIDATION";

    /** Upper bound on errors listed in the response body; the rest are reported as a count. */
    public static final int MAX_LISTED_ERRORS = 20;

    private final transient String graphName;
    private final transient int errorCount;
    private final transient List<ValidationErrorSummaryDto> errors;

    public OntologyDownloadBlockedException(String graphName, int errorCount, List<ValidationErrorSummaryDto> errors) {
        super(buildMessage(errorCount));
        this.graphName = graphName;
        this.errorCount = errorCount;
        this.errors = errors;
    }

    private static String buildMessage(int errorCount) {
        return "Slovník nelze stáhnout, protože obsahuje " + errorCount + " " + pluralizeErrors(errorCount)
                + " z kontroly. Opravte je a spusťte kontrolu znovu.";
    }

    /** Czech needs three forms: 1 chybu, 2-4 chyby, 0 or 5+ chyb. */
    private static String pluralizeErrors(int count) {
        if (count == 1) {
            return "chybu";
        }
        return count >= 2 && count <= 4 ? "chyby" : "chyb";
    }

    public String getGraphName() {
        return graphName;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public List<ValidationErrorSummaryDto> getErrors() {
        return errors;
    }

    public boolean isTruncated() {
        return errorCount > errors.size();
    }
}