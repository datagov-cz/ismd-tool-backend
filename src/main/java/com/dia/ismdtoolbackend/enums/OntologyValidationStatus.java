package com.dia.ismdtoolbackend.enums;

/**
 * The outcome of the most recent validation attempt for an ontology. Lets the FE distinguish
 * "validated" from "never validated because the validator was unavailable at upload" and offer
 * a manual re-validation in the latter case — the tool ingests the ontology regardless of
 * validator availability.
 */
public enum OntologyValidationStatus {

    /** The validator evaluated the ontology (the report itself carries any findings). */
    VALIDATED,

    /** Upload-time validation was skipped because the validator was unavailable; re-run manually. */
    SKIPPED_UNAVAILABLE,

    /** A validation attempt failed for a reason other than plain unavailability. */
    FAILED
}
