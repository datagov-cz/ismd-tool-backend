package com.dia.ismdtoolbackend.enums;

import com.dia.constants.VocabularyConstants;
import com.dia.ismdtoolbackend.exception.ConflictingConceptTypeException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ConceptTypeTest {

    private static final String OFN_TRIDA = VocabularyConstants.OFN_NAMESPACE + VocabularyConstants.TRIDA;
    private static final String OFN_VLASTNOST = VocabularyConstants.OFN_NAMESPACE + VocabularyConstants.VLASTNOST;
    private static final String OFN_VZTAH = VocabularyConstants.OFN_NAMESPACE + VocabularyConstants.VZTAH;
    private static final String OWL_CLASS = "http://www.w3.org/2002/07/owl#Class";
    private static final String OWL_OBJECT_PROPERTY = "http://www.w3.org/2002/07/owl#ObjectProperty";
    private static final String OWL_DATATYPE_PROPERTY = "http://www.w3.org/2002/07/owl#DatatypeProperty";

    @Test
    void fromRdfTypes_resolvesOfnRoleTags() {
        assertThat(ConceptType.fromRdfTypes(List.of(OFN_TRIDA))).isEqualTo(ConceptType.TRIDA);
        assertThat(ConceptType.fromRdfTypes(List.of(OFN_VLASTNOST))).isEqualTo(ConceptType.VLASTNOST);
        assertThat(ConceptType.fromRdfTypes(List.of(OFN_VZTAH))).isEqualTo(ConceptType.VZTAH);
    }

    @Test
    void fromRdfTypes_resolvesOwlTypes() {
        assertThat(ConceptType.fromRdfTypes(List.of(OWL_CLASS))).isEqualTo(ConceptType.TRIDA);
        assertThat(ConceptType.fromRdfTypes(List.of(OWL_DATATYPE_PROPERTY))).isEqualTo(ConceptType.VLASTNOST);
        assertThat(ConceptType.fromRdfTypes(List.of(OWL_OBJECT_PROPERTY))).isEqualTo(ConceptType.VZTAH);
    }

    @Test
    void fromRdfTypes_resolvesShortJsonLdLabels() {
        // Shape the detail extractor actually produces (ConceptDetailModel.types):
        // the base labels plus one role label.
        assertThat(ConceptType.fromRdfTypes(
                List.of(VocabularyConstants.POJEM_JSON_LD, "Koncept", VocabularyConstants.TRIDA_JSON_LD)))
                .isEqualTo(ConceptType.TRIDA);
        assertThat(ConceptType.fromRdfTypes(
                List.of(VocabularyConstants.POJEM_JSON_LD, "Koncept", VocabularyConstants.VLASTNOST_JSON_LD)))
                .isEqualTo(ConceptType.VLASTNOST);
        assertThat(ConceptType.fromRdfTypes(
                List.of(VocabularyConstants.POJEM_JSON_LD, "Koncept", VocabularyConstants.VZTAH_JSON_LD)))
                .isEqualTo(ConceptType.VZTAH);
    }

    @Test
    void fromRdfTypes_sameRoleInEveryVocabulary_isOneRole() {
        assertThat(ConceptType.fromRdfTypes(
                List.of(OWL_OBJECT_PROPERTY, OFN_VZTAH, VocabularyConstants.VZTAH_JSON_LD)))
                .isEqualTo(ConceptType.VZTAH);
    }

    @Test
    void fromRdfTypes_twoRoles_throwInEitherOrder() {
        for (List<String> types : List.of(List.of(OFN_VLASTNOST, OWL_OBJECT_PROPERTY),
                List.of(OWL_OBJECT_PROPERTY, OFN_VLASTNOST),
                List.of(VocabularyConstants.TRIDA_JSON_LD, VocabularyConstants.VZTAH_JSON_LD))) {
            ConflictingConceptTypeException e = assertThatExceptionOfType(ConflictingConceptTypeException.class)
                    .as(types::toString)
                    .isThrownBy(() -> ConceptType.fromRdfTypes(types))
                    .actual();
            assertThat(e).as(e.getMessage()).hasMessageContaining(" + ");
        }
    }

    @Test
    void fromRdfTypes_fallsBackToKoncept_whenOnlyBaseLabels() {
        // A concept carrying only the base type labels (no role) → generic KONCEPT.
        assertThat(ConceptType.fromRdfTypes(List.of(VocabularyConstants.POJEM_JSON_LD, "Koncept")))
                .isEqualTo(ConceptType.KONCEPT);
    }

    @Test
    void fromRdfTypes_fallsBackToKoncept_whenNoRoleMarker() {
        assertThat(ConceptType.fromRdfTypes(List.of("http://www.w3.org/2004/02/skos/core#Concept")))
                .isEqualTo(ConceptType.KONCEPT);
    }

    @Test
    void fromRdfTypes_fallsBackToKoncept_whenNullOrEmpty() {
        assertThat(ConceptType.fromRdfTypes(null)).isEqualTo(ConceptType.KONCEPT);
        assertThat(ConceptType.fromRdfTypes(List.of())).isEqualTo(ConceptType.KONCEPT);
    }

    @Test
    void fromString_roundTripsAllValues() {
        for (ConceptType type : ConceptType.values()) {
            assertThat(ConceptType.fromString(type.getValue())).isEqualTo(type);
        }
    }
}