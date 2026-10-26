package com.dia.ismdtoolbackend.enums;

import com.dia.constants.VocabularyConstants;
import com.dia.ismdtoolbackend.exception.ConflictingConceptTypeException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConceptTypeTest {

    private static final String OFN_TRIDA = VocabularyConstants.OFN_NAMESPACE + VocabularyConstants.TRIDA;
    private static final String OFN_VLASTNOST = VocabularyConstants.OFN_NAMESPACE + VocabularyConstants.VLASTNOST;
    private static final String OFN_VZTAH = VocabularyConstants.OFN_NAMESPACE + VocabularyConstants.VZTAH;
    private static final String OWL_CLASS = "http://www.w3.org/2002/07/owl#Class";
    private static final String OWL_OBJECT_PROPERTY = "http://www.w3.org/2002/07/owl#ObjectProperty";
    private static final String OWL_DATATYPE_PROPERTY = "http://www.w3.org/2002/07/owl#DatatypeProperty";

    @Test
    void fromRdfTypes_resolvesOfnRoleTags() {
        assertEquals(ConceptType.TRIDA, ConceptType.fromRdfTypes(List.of(OFN_TRIDA)));
        assertEquals(ConceptType.VLASTNOST, ConceptType.fromRdfTypes(List.of(OFN_VLASTNOST)));
        assertEquals(ConceptType.VZTAH, ConceptType.fromRdfTypes(List.of(OFN_VZTAH)));
    }

    @Test
    void fromRdfTypes_resolvesOwlTypes() {
        assertEquals(ConceptType.TRIDA, ConceptType.fromRdfTypes(List.of(OWL_CLASS)));
        assertEquals(ConceptType.VLASTNOST, ConceptType.fromRdfTypes(List.of(OWL_DATATYPE_PROPERTY)));
        assertEquals(ConceptType.VZTAH, ConceptType.fromRdfTypes(List.of(OWL_OBJECT_PROPERTY)));
    }

    @Test
    void fromRdfTypes_resolvesShortJsonLdLabels() {
        // Shape the detail extractor actually produces (ConceptDetailModel.types):
        // the base labels plus one role label.
        assertEquals(ConceptType.TRIDA, ConceptType.fromRdfTypes(
                List.of(VocabularyConstants.POJEM_JSON_LD, "Koncept", VocabularyConstants.TRIDA_JSON_LD)));
        assertEquals(ConceptType.VLASTNOST, ConceptType.fromRdfTypes(
                List.of(VocabularyConstants.POJEM_JSON_LD, "Koncept", VocabularyConstants.VLASTNOST_JSON_LD)));
        assertEquals(ConceptType.VZTAH, ConceptType.fromRdfTypes(
                List.of(VocabularyConstants.POJEM_JSON_LD, "Koncept", VocabularyConstants.VZTAH_JSON_LD)));
    }

    @Test
    void fromRdfTypes_sameRoleInEveryVocabulary_isOneRole() {
        assertEquals(ConceptType.VZTAH, ConceptType.fromRdfTypes(
                List.of(OWL_OBJECT_PROPERTY, OFN_VZTAH, VocabularyConstants.VZTAH_JSON_LD)));
    }

    @Test
    void fromRdfTypes_twoRoles_throwInEitherOrder() {
        for (List<String> types : List.of(List.of(OFN_VLASTNOST, OWL_OBJECT_PROPERTY),
                List.of(OWL_OBJECT_PROPERTY, OFN_VLASTNOST),
                List.of(VocabularyConstants.TRIDA_JSON_LD, VocabularyConstants.VZTAH_JSON_LD))) {
            ConflictingConceptTypeException e = assertThrows(ConflictingConceptTypeException.class,
                    () -> ConceptType.fromRdfTypes(types), types::toString);
            assertTrue(e.getMessage().contains(" + "), e.getMessage());
        }
    }

    @Test
    void fromRdfTypes_fallsBackToKoncept_whenOnlyBaseLabels() {
        // A concept carrying only the base type labels (no role) → generic KONCEPT.
        assertEquals(ConceptType.KONCEPT,
                ConceptType.fromRdfTypes(List.of(VocabularyConstants.POJEM_JSON_LD, "Koncept")));
    }

    @Test
    void fromRdfTypes_fallsBackToKoncept_whenNoRoleMarker() {
        assertEquals(ConceptType.KONCEPT,
                ConceptType.fromRdfTypes(List.of("http://www.w3.org/2004/02/skos/core#Concept")));
    }

    @Test
    void fromRdfTypes_fallsBackToKoncept_whenNullOrEmpty() {
        assertEquals(ConceptType.KONCEPT, ConceptType.fromRdfTypes(null));
        assertEquals(ConceptType.KONCEPT, ConceptType.fromRdfTypes(List.of()));
    }

    @Test
    void fromString_roundTripsAllValues() {
        for (ConceptType type : ConceptType.values()) {
            assertEquals(type, ConceptType.fromString(type.getValue()));
        }
    }
}