package com.dia.ismdtoolbackend.exception;

import java.util.List;

/** The uploaded data types some concepts as both a property and a relationship. Nothing is persisted. */
public class OntologyUploadConflictingTypesException extends OntologyUploadException {
    private final transient List<String> conceptIris;

    public OntologyUploadConflictingTypesException(List<String> conceptIris) {
        super("Nahraný slovník obsahuje pojmy typované zároveň jako vlastnost i vztah: "
                + String.join(", ", conceptIris));
        this.conceptIris = List.copyOf(conceptIris);
    }

    public List<String> getConceptIris() {
        return conceptIris;
    }
}
