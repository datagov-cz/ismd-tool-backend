package com.dia.ismdtoolbackend.models.concept;

import org.apache.jena.ontology.OntologyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ConceptValidationUtil - code list (číselník)")
class ConceptValidationUtilCodeListTest {

    private static final String CODE_LIST_IRI =
            "https://data.mvcr.gov.cz/zdroj/číselníky/typy-turistických-cílů";
    private static final String NKOD_DATASET =
            "https://data.gov.cz/zdroj/datové-sady/17651921/ff931872553062c9890157ce8615af03";

    @Nested
    @DisplayName("validateCodeListCompleteness")
    class Completeness {

        @Test
        void acceptsBothPresent() {
            assertDoesNotThrow(() ->
                    ConceptValidationUtil.validateCodeListCompleteness(CODE_LIST_IRI, NKOD_DATASET));
        }

        @Test
        void acceptsBothAbsent() {
            assertDoesNotThrow(() ->
                    ConceptValidationUtil.validateCodeListCompleteness(null, null));
        }

        @Test
        void acceptsBothBlank() {
            assertDoesNotThrow(() ->
                    ConceptValidationUtil.validateCodeListCompleteness("   ", ""));
        }

        @Test
        void rejectsIriWithoutDataset() {
            assertThrows(OntologyException.class, () ->
                    ConceptValidationUtil.validateCodeListCompleteness(CODE_LIST_IRI, null));
        }

        @Test
        void rejectsDatasetWithoutIri() {
            assertThrows(OntologyException.class, () ->
                    ConceptValidationUtil.validateCodeListCompleteness(null, NKOD_DATASET));
        }

        @Test
        void treatsBlankDatasetAsAbsent() {
            assertThrows(OntologyException.class, () ->
                    ConceptValidationUtil.validateCodeListCompleteness(CODE_LIST_IRI, "   "));
        }
    }

    @Nested
    @DisplayName("validateCodeListIri")
    class IriFormat {

        @Test
        void acceptsRawUtf8CzechIri() {
            assertDoesNotThrow(() -> ConceptValidationUtil.validateCodeListIri(CODE_LIST_IRI));
        }

        @Test
        void toleratesNullAndBlank() {
            assertDoesNotThrow(() -> ConceptValidationUtil.validateCodeListIri(null));
            assertDoesNotThrow(() -> ConceptValidationUtil.validateCodeListIri("  "));
        }

        @Test
        void rejectsNonAbsoluteIri() {
            assertThrows(OntologyException.class, () ->
                    ConceptValidationUtil.validateCodeListIri("typy-turistických-cílů"));
        }

        @Test
        void rejectsIriContainingWhitespace() {
            assertThrows(OntologyException.class, () ->
                    ConceptValidationUtil.validateCodeListIri("https://data.mvcr.gov.cz/a b"));
        }

        @Test
        void rejectsNonHttpScheme() {
            assertThrows(OntologyException.class, () ->
                    ConceptValidationUtil.validateCodeListIri("ftp://data.mvcr.gov.cz/x"));
        }
    }
}
