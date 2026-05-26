package com.dia.ismdtoolbackend.enums;

import com.dia.ismdtoolbackend.controller.dto.DataTypeDto;

import java.util.Optional;

/**
 * v1 codelist of datatypes that ISMD properties (vlastnost) may carry.
 * Single source of truth for the {@code code ⇄ IRI ⇄ Czech label} mapping
 * driving the FE datatype dropdown and the {@code obor-hodnot-resolved}
 * field on concept-detail responses.
 *
 * <p>Declaration order is the order shown to the FE.
 * {@link #LITERAL} is the read-side default when an incoming value is
 * malformed/empty/unrecognised — mirrors the write-side default in
 * {@code ConceptCreator.addRangeProperty}.
 */
public enum PropertyDataType {
    BOOLEAN("boolean", "http://www.w3.org/2001/XMLSchema#boolean", "Ano či ne"),
    DATE("date", "http://www.w3.org/2001/XMLSchema#date", "Datum"),
    TIME("time", "http://www.w3.org/2001/XMLSchema#time", "Čas"),
    DATE_TIME_STAMP("dateTimeStamp", "http://www.w3.org/2001/XMLSchema#dateTimeStamp", "Datum a čas"),
    INTEGER("integer", "http://www.w3.org/2001/XMLSchema#integer", "Celé číslo"),
    DOUBLE("double", "http://www.w3.org/2001/XMLSchema#double", "Desetinné číslo"),
    ANY_URI("anyURI", "http://www.w3.org/2001/XMLSchema#anyURI", "URI, IRI, URL"),
    STRING("string", "http://www.w3.org/2001/XMLSchema#string", "Řetězec"),
    LITERAL("Literal", "http://www.w3.org/2000/01/rdf-schema#Literal", "Text");

    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";
    private static final String RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String XSD_PREFIX = "xsd:";
    private static final String RDFS_PREFIX = "rdfs:";

    private final String code;
    private final String iri;
    private final String label;

    PropertyDataType(String code, String iri, String label) {
        this.code = code;
        this.iri = iri;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String iri() {
        return iri;
    }

    public String label() {
        return label;
    }

    public DataTypeDto toDto() {
        return new DataTypeDto(code, label);
    }

    /**
     * Resolve any of: bare code, {@code xsd:}/{@code rdfs:} prefixed form,
     * full IRI, or Czech label. Returns empty for null/blank/unknown — callers
     * decide whether to fall back to {@link #LITERAL}.
     */
    public static Optional<PropertyDataType> fromValue(String raw) {
        if (raw == null) return Optional.empty();
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return Optional.empty();

        String candidate = trimmed;
        if (candidate.startsWith(XSD_NS)) {
            candidate = candidate.substring(XSD_NS.length());
        } else if (candidate.startsWith(RDFS_NS)) {
            candidate = candidate.substring(RDFS_NS.length());
        } else if (candidate.startsWith(XSD_PREFIX)) {
            candidate = candidate.substring(XSD_PREFIX.length());
        } else if (candidate.startsWith(RDFS_PREFIX)) {
            candidate = candidate.substring(RDFS_PREFIX.length());
        }

        for (PropertyDataType t : values()) {
            if (t.code.equals(candidate) || t.iri.equals(trimmed)) {
                return Optional.of(t);
            }
        }
        for (PropertyDataType t : values()) {
            if (t.label.equalsIgnoreCase(trimmed)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
