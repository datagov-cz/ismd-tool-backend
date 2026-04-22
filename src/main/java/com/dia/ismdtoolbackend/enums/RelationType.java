package com.dia.ismdtoolbackend.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(type = "string", allowableValues = {"SUBCLASS", "SUPERCLASS", "EXACT_MATCH", "PROPERTY_OF", "RELATIONSHIP_OF"})
public enum RelationType {
    SUBCLASS("SUBCLASS"),
    SUPERCLASS("SUPERCLASS"),
    EXACT_MATCH("EXACT_MATCH"),
    PROPERTY_OF("PROPERTY_OF"),
    RELATIONSHIP_OF("RELATIONSHIP_OF");

    private final String value;

    RelationType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static RelationType fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        for (RelationType type : RelationType.values()) {
            if (type.value.equalsIgnoreCase(trimmed)) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "Unknown relation type: " + value + ". Valid values: SUBCLASS, SUPERCLASS, EXACT_MATCH, PROPERTY_OF, RELATIONSHIP_OF"
        );
    }

    @Override
    public String toString() {
        return value;
    }
}
