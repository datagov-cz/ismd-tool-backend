package com.dia.ismdtoolbackend.enums;

/**
 * How the upload should treat owned concepts that are missing {@code skos:inScheme}.
 * Sent by the FE on re-upload after a {@code MISSING_INSCHEME_DECISION_REQUIRED}
 * response. When absent, an upload with missing-inScheme concepts is rejected with
 * that response (the user must make a decision).
 */
public enum NormalizeMode {
    /** Add {@code inScheme → graphName} for every missing owned concept. */
    NORMALIZE_ALL,
    /** Leave every missing concept without inScheme (no ownership row; triples kept). */
    EXCLUDE_ALL,
    /** Normalize only the concepts listed in {@code conceptsToNormalize}; exclude the rest. */
    PER_CONCEPT
}
