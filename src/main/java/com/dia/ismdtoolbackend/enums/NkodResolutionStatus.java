package com.dia.ismdtoolbackend.enums;

/** Outcome of resolving a codelist dataset to its codelist IRI. */
public enum NkodResolutionStatus {
    /** The codelist IRI was read from the dataset's distribution. */
    RESOLVED,
    /** The dataset is known, but no distribution yielded an absolute codelist IRI. */
    UNRESOLVED,
    /** The dataset is not in the codelist snapshot. */
    UNKNOWN_DATASET
}
