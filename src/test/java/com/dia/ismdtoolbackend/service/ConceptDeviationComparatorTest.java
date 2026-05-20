package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.NonLegalSourceDto;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.service.impl.ConceptDeviationComparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConceptDeviationComparatorTest {

    private ConceptDeviationComparator comparator;

    @BeforeEach
    void setUp() {
        comparator = new ConceptDeviationComparator();
    }

    private OntologyDetailModel.ConceptDetailModel.ConceptDetailModelBuilder minimalConcept() {
        return OntologyDetailModel.ConceptDetailModel.builder();
    }

    @Nested
    class IdenticalConcepts {

        @Test
        void identicalConcepts_shouldReturnNoDeviation() {
            OntologyDetailModel.ConceptDetailModel concept = minimalConcept()
                    .types(List.of("Type1"))
                    .name(Map.of("cs", "Název"))
                    .definition(Map.of("cs", "Definice"))
                    .identifier("ID-1")
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(concept, concept);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.NO_DEVIATION, result.getStatus());
        }

        @Test
        void allFieldsIdentical_shouldReturnNoDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .types(List.of("Type1", "Type2"))
                    .name(Map.of("cs", "Název", "en", "Name"))
                    .alternativeName(Map.of("cs", "Alt"))
                    .definition(Map.of("cs", "Def"))
                    .description(Map.of("cs", "Desc"))
                    .identifier("ID-1")
                    .domain("domain1")
                    .range("range1")
                    .broaderClasses(List.of("broader1"))
                    .exactMatches(List.of("match1"))
                    .definingLegalSources(List.of("legal1"))
                    .sharingMethods(List.of("method1"))
                    .acquisitionMethod("acq1")
                    .contentType("type1")
                    .isPpdf(true)
                    .ais("ais1")
                    .agenda("agenda1")
                    .privacyProvisions(List.of("prov1"))
                    .build();

            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .types(List.of("Type1", "Type2"))
                    .name(Map.of("cs", "Název", "en", "Name"))
                    .alternativeName(Map.of("cs", "Alt"))
                    .definition(Map.of("cs", "Def"))
                    .description(Map.of("cs", "Desc"))
                    .identifier("ID-1")
                    .domain("domain1")
                    .range("range1")
                    .broaderClasses(List.of("broader1"))
                    .exactMatches(List.of("match1"))
                    .definingLegalSources(List.of("legal1"))
                    .sharingMethods(List.of("method1"))
                    .acquisitionMethod("acq1")
                    .contentType("type1")
                    .isPpdf(true)
                    .ais("ais1")
                    .agenda("agenda1")
                    .privacyProvisions(List.of("prov1"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.NO_DEVIATION, result.getStatus());
        }
    }

    @Nested
    class NullValueHandling {

        @Test
        void bothFieldsNull_shouldReturnNoDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept().build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept().build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.NO_DEVIATION, result.getStatus());
        }

        @Test
        void localNull_publishedNotNull_shouldDetectDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .name(null)
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .name(Map.of("cs", "Název"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNotNull(result.getName());
            assertTrue(result.getName().isDifferent());
            assertNull(result.getName().getLocalValue());
            assertEquals(Map.of("cs", "Název"), result.getName().getPublishedValue());
        }

        @Test
        void localNotNull_publishedNull_shouldDetectDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .identifier("ID-1")
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .identifier(null)
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNotNull(result.getIdentifier());
            assertTrue(result.getIdentifier().isDifferent());
        }

        @Test
        void localNullList_publishedNotNull_shouldDetectDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .types(null)
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .types(List.of("Type1"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNotNull(result.getTypes());
            assertTrue(result.getTypes().isDifferent());
        }
    }

    @Nested
    class EmptyCollections {

        @Test
        void bothEmptyLists_shouldReturnNoDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .types(Collections.emptyList())
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .types(Collections.emptyList())
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.NO_DEVIATION, result.getStatus());
        }

        @Test
        void emptyListVsPopulatedList_shouldDetectDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .types(Collections.emptyList())
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .types(List.of("Type1"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
        }

        @Test
        void bothEmptyMaps_shouldReturnNoDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .name(Collections.emptyMap())
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .name(Collections.emptyMap())
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.NO_DEVIATION, result.getStatus());
        }
    }

    @Nested
    class ListOrderIndependence {

        @Test
        void listsWithSameElementsDifferentOrder_shouldReturnNoDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .types(List.of("Type1", "Type2", "Type3"))
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .types(List.of("Type3", "Type1", "Type2"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.NO_DEVIATION, result.getStatus());
        }

        @Test
        void listsWithDifferentElements_shouldDetectDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .broaderClasses(List.of("class1", "class2"))
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .broaderClasses(List.of("class1", "class3"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNotNull(result.getBroaderClasses());
            assertTrue(result.getBroaderClasses().isDifferent());
        }
    }

    @Nested
    class MapComparison {

        @Test
        void mapsWithDifferentLanguageTags_shouldDetectDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .name(Map.of("cs", "Název"))
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .name(Map.of("en", "Name"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNotNull(result.getName());
            assertTrue(result.getName().isDifferent());
        }

        @Test
        void mapsWithSameKeysDifferentValues_shouldDetectDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .definition(Map.of("cs", "Definice A"))
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .definition(Map.of("cs", "Definice B"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNotNull(result.getDefinition());
            assertTrue(result.getDefinition().isDifferent());
        }

        @Test
        void mapsWithExtraLanguageTag_shouldDetectDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .name(Map.of("cs", "Název", "en", "Name"))
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .name(Map.of("cs", "Název"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
        }
    }

    @Nested
    class BooleanAndStringFields {

        @Test
        void differentBooleanValues_shouldDetectDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .isPpdf(true)
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .isPpdf(false)
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNotNull(result.getIsPpdf());
            assertTrue(result.getIsPpdf().isDifferent());
        }

        @Test
        void differentStringValues_shouldDetectDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .domain("domain-local")
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .domain("domain-published")
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNotNull(result.getDomain());
            assertEquals("domain-local", result.getDomain().getLocalValue());
            assertEquals("domain-published", result.getDomain().getPublishedValue());
        }
    }

    @Nested
    class NonLegalSourcesComparison {

        @Test
        void identicalNonLegalSources_shouldReturnNoDeviation() {
            NonLegalSourceDto source = NonLegalSourceDto.builder()
                    .url("https://example.com")
                    .nazev(Map.of("cs", "Source"))
                    .build();
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .definingNonLegalSources(List.of(source))
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .definingNonLegalSources(List.of(source))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.NO_DEVIATION, result.getStatus());
        }

        @Test
        void differentNonLegalSources_shouldDetectDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .definingNonLegalSources(List.of(NonLegalSourceDto.builder().url("https://a.com").build()))
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .definingNonLegalSources(List.of(NonLegalSourceDto.builder().url("https://b.com").build()))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
        }
    }

    @Nested
    class MixedDeviations {

        @Test
        void singleFieldDifferent_shouldDetectDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .types(List.of("Type1"))
                    .name(Map.of("cs", "Same"))
                    .identifier("same-id")
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .types(List.of("Type1"))
                    .name(Map.of("cs", "Same"))
                    .identifier("different-id")
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNull(result.getTypes());
            assertNull(result.getName());
            assertNotNull(result.getIdentifier());
        }
    }
}
