package com.dia.ismdtoolbackend.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(type = "string", allowableValues = {"ONTOLOGY", "CONCEPT"})
public enum SearchType {
    ONTOLOGY("ONTOLOGY"),
    CONCEPT("CONCEPT");

    private final String value;

    SearchType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static SearchType fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        for (SearchType type : SearchType.values()) {
            if (type.value.equalsIgnoreCase(trimmed)) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "Unknown search type: " + value + ". Valid values: ONTOLOGY, CONCEPT"
        );
    }

    @Override
    public String toString() {
        return value;
    }
}
