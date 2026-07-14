package com.dia.ismdtoolbackend.enums;

import com.dia.constants.VocabularyConstants;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.jena.ontology.OntologyException;

import java.util.List;

@Schema(type = "string", allowableValues = {"TRIDA", "VLASTNOST", "VZTAH", "KONCEPT"})
public enum ConceptType {
    TRIDA("TRIDA"),
    VLASTNOST("VLASTNOST"),
    VZTAH("VZTAH"),
    KONCEPT("KONCEPT");

    private static final String OWL_CLASS = "http://www.w3.org/2002/07/owl#Class";
    private static final String OWL_OBJECT_PROPERTY = "http://www.w3.org/2002/07/owl#ObjectProperty";
    private static final String OWL_DATATYPE_PROPERTY = "http://www.w3.org/2002/07/owl#DatatypeProperty";

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
                "Neznámý typ pojmu: " + value + ". Platné hodnoty: TRIDA, VLASTNOST, VZTAH, KONCEPT"
        );
    }

    @Override
    public String toString() {
        return value;
    }

    /**
     * Resolves the concept role from a concept's type set. Accepts three shapes,
     * because callers feed different vocabularies:
     *   - the OFN role IRI (slovníky:třída/vlastnost/vztah — what ISMD writes),
     *   - the matching OWL type (owl:Class/DatatypeProperty/ObjectProperty — what
     *     externally imported or NKD data may carry),
     *   - the short OFN JSON-LD label (Třída/Vlastnost/Vztah — what the detail
     *     extractor's ConceptDetailModel.types carries).
     * Falls back to KONCEPT when no specific role marker is present, so the field
     * is never null.
     */
    public static ConceptType fromRdfTypes(List<String> types) {
        if (types == null) {
            return KONCEPT;
        }
        String tridaIri = VocabularyConstants.OFN_NAMESPACE + VocabularyConstants.TRIDA;
        String vlastnostIri = VocabularyConstants.OFN_NAMESPACE + VocabularyConstants.VLASTNOST;
        String vztahIri = VocabularyConstants.OFN_NAMESPACE + VocabularyConstants.VZTAH;
        for (String t : types) {
            if (tridaIri.equals(t) || OWL_CLASS.equals(t)
                    || VocabularyConstants.TRIDA_JSON_LD.equals(t)) return TRIDA;
            if (vlastnostIri.equals(t) || OWL_DATATYPE_PROPERTY.equals(t)
                    || VocabularyConstants.VLASTNOST_JSON_LD.equals(t)) return VLASTNOST;
            if (vztahIri.equals(t) || OWL_OBJECT_PROPERTY.equals(t)
                    || VocabularyConstants.VZTAH_JSON_LD.equals(t)) return VZTAH;
        }
        return KONCEPT;
    }
}
