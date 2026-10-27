package com.dia.ismdtoolbackend.models.concept;

import org.apache.jena.ontology.OntologyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

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
            assertThatCode(() ->
                    ConceptValidationUtil.validateCodeListCompleteness(CODE_LIST_IRI, NKOD_DATASET))
                    .doesNotThrowAnyException();
        }

        @Test
        void acceptsBothAbsent() {
            assertThatCode(() ->
                    ConceptValidationUtil.validateCodeListCompleteness(null, null))
                    .doesNotThrowAnyException();
        }

        @Test
        void acceptsBothBlank() {
            assertThatCode(() ->
                    ConceptValidationUtil.validateCodeListCompleteness("   ", ""))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsIriWithoutDataset() {
            assertThatThrownBy(() ->
                    ConceptValidationUtil.validateCodeListCompleteness(CODE_LIST_IRI, null))
                    .isInstanceOf(OntologyException.class);
        }

        @Test
        void rejectsDatasetWithoutIri() {
            assertThatThrownBy(() ->
                    ConceptValidationUtil.validateCodeListCompleteness(null, NKOD_DATASET))
                    .isInstanceOf(OntologyException.class);
        }

        @Test
        void treatsBlankDatasetAsAbsent() {
            assertThatThrownBy(() ->
                    ConceptValidationUtil.validateCodeListCompleteness(CODE_LIST_IRI, "   "))
                    .isInstanceOf(OntologyException.class);
        }
    }

    @Nested
    @DisplayName("validateCodeListDataset")
    class DatasetFormat {

        @Test
        void acceptsRawUtf8DatasetIri() {
            assertThatCode(() -> ConceptValidationUtil.validateCodeListDataset(NKOD_DATASET))
                    .doesNotThrowAnyException();
        }

        @Test
        void acceptsPercentEncodedDatasetIri() {
            assertThatCode(() -> ConceptValidationUtil.validateCodeListDataset(
                    NKOD_DATASET.replace("datové-sady", "datov%C3%A9-sady")))
                    .doesNotThrowAnyException();
        }

        @Test
        void toleratesNullAndBlank() {
            assertThatCode(() -> ConceptValidationUtil.validateCodeListDataset(null)).doesNotThrowAnyException();
            assertThatCode(() -> ConceptValidationUtil.validateCodeListDataset("  ")).doesNotThrowAnyException();
        }

        @Test
        void rejectsADatasetOutsideNkod() {
            assertThatThrownBy(() ->
                    ConceptValidationUtil.validateCodeListDataset("https://not-nkod.example/dataset/x"))
                    .isInstanceOf(OntologyException.class);
        }

        @Test
        void rejectsAnEncodedIriThatIsNotAnNkodDataset() {
            assertThatThrownBy(() ->
                    ConceptValidationUtil.validateCodeListDataset(
                            "https://data.gov.cz/zdroj/datov%C3%A9-s%C3%A1dy/x"))
                    .isInstanceOf(OntologyException.class);
        }

        @Test
        void rejectsAMalformedEscape() {
            assertThatThrownBy(() ->
                    ConceptValidationUtil.validateCodeListDataset("https://data.gov.cz/zdroj/datov%zz-sady/x"))
                    .isInstanceOf(OntologyException.class);
        }
    }

    @Nested
    @DisplayName("validateCodeListIri")
    class IriFormat {

        @Test
        void acceptsRawUtf8CzechIri() {
            assertThatCode(() -> ConceptValidationUtil.validateCodeListIri(CODE_LIST_IRI)).doesNotThrowAnyException();
        }

        @Test
        void toleratesNullAndBlank() {
            assertThatCode(() -> ConceptValidationUtil.validateCodeListIri(null)).doesNotThrowAnyException();
            assertThatCode(() -> ConceptValidationUtil.validateCodeListIri("  ")).doesNotThrowAnyException();
        }

        @Test
        void rejectsNonAbsoluteIri() {
            assertThatThrownBy(() ->
                    ConceptValidationUtil.validateCodeListIri("typy-turistických-cílů"))
                    .isInstanceOf(OntologyException.class);
        }

        @Test
        void rejectsIriContainingWhitespace() {
            assertThatThrownBy(() ->
                    ConceptValidationUtil.validateCodeListIri("https://data.mvcr.gov.cz/a b"))
                    .isInstanceOf(OntologyException.class);
        }

        @Test
        void rejectsNonHttpScheme() {
            assertThatThrownBy(() ->
                    ConceptValidationUtil.validateCodeListIri("ftp://data.mvcr.gov.cz/x"))
                    .isInstanceOf(OntologyException.class);
        }
    }
}
