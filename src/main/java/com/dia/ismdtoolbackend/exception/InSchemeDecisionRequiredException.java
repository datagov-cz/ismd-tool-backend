package com.dia.ismdtoolbackend.exception;

import com.dia.ismdtoolbackend.controller.dto.MissingConceptDto;

import java.util.List;

/**
 * Thrown on upload when owned concepts lack {@code skos:inScheme} and no
 * {@code normalizeMode} decision was supplied. Carries the data needed to render the
 * {@code MISSING_INSCHEME_DECISION_REQUIRED} (HTTP 400) response so the FE can prompt
 * the user. Nothing has been persisted when this is thrown.
 */
public class InSchemeDecisionRequiredException extends RuntimeException {

    /** Stable error code the FE branches on (not the localized message). */
    public static final String ERROR_CODE = "MISSING_INSCHEME_DECISION_REQUIRED";

    private final transient String graphName;
    private final transient List<MissingConceptDto> conceptsMissingInScheme;

    public InSchemeDecisionRequiredException(String graphName, List<MissingConceptDto> conceptsMissingInScheme) {
        super("Některé pojmy nemají skos:inScheme. Vyberte způsob zpracování (normalizovat nebo vyloučit).");
        this.graphName = graphName;
        this.conceptsMissingInScheme = conceptsMissingInScheme;
    }

    public String getGraphName() {
        return graphName;
    }

    public List<MissingConceptDto> getConceptsMissingInScheme() {
        return conceptsMissingInScheme;
    }
}
