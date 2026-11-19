package com.dia.ismdtoolbackend.enums;

/** How a concept's stored codelist compares with the NKOD codelist snapshot. */
public enum NkodCodelistStatus {
    /** The stored codelist IRI is the dataset's current one. */
    CURRENT,
    /** The dataset publishes a different codelist IRI than the stored one. */
    NEW_VERSION,
    /** The stored dataset is no longer in the catalogue. */
    MISSING
}
