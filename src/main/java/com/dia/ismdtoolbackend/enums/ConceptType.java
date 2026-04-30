package com.dia.ismdtoolbackend.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.jena.ontology.OntologyException;

@Schema(type = "string", allowableValues = {"TRIDA", "VLASTNOST", "VZTAH"})
public enum ConceptType {
    TRIDA("TRIDA"),
    VLASTNOST("VLASTNOST"),
    VZTAH("VZTAH");

    private final String value;

    ConceptType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ConceptType fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new OntologyException("Typ pojmu nesmí být prázdný");
        }

        for (ConceptType type : ConceptType.values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }

        throw new OntologyException(
                "Neznámý typ pojmu: " + value + ". Platné hodnoty: TRIDA, VLASTNOST, VZTAH"
        );
    }

    @Override
    public String toString() {
        return value;
    }
}
