package com.dia.ismdtoolbackend.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(type = "string", allowableValues = {"ONTOLOGY", "CONCEPT", "CLASS", "PROPERTY", "RELATIONSHIP"})
public enum SearchType {
    ONTOLOGY("ONTOLOGY"),
    CONCEPT("CONCEPT"),
    CLASS("CLASS"),
    PROPERTY("PROPERTY"),
    RELATIONSHIP("RELATIONSHIP");

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
                "Unknown search type: " + value + ". Valid values: ONTOLOGY, CONCEPT, CLASS, PROPERTY, RELATIONSHIP"
        );
    }

    /**
     * Maps a role-narrowing SearchType to its corresponding ConceptType.
     * Returns null for ONTOLOGY and CONCEPT — those are not role filters.
     */
    public ConceptType toConceptType() {
        return switch (this) {
            case CLASS -> ConceptType.TRIDA;
            case PROPERTY -> ConceptType.VLASTNOST;
            case RELATIONSHIP -> ConceptType.VZTAH;
            case ONTOLOGY, CONCEPT -> null;
        };
    }

    /** True when this restricts the result set to concepts of a single role. */
    public boolean isConceptRoleFilter() {
        return this == CLASS || this == PROPERTY || this == RELATIONSHIP;
    }

    /** True when this accepts concept rows (CONCEPT or any role narrowing). */
    public boolean isAnyConcept() {
        return this == CONCEPT || isConceptRoleFilter();
    }

    @Override
    public String toString() {
        return value;
    }
}
