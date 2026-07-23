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
    class ObjectSubjectAndPublicPrivate {

        // The type-list labels these pairs are derived from (VocabularyConstants *_JSON_LD).
        private static final String TOP = "Typ objektu práva";
        private static final String TSP = "Typ subjektu práva";
        private static final String VEREJNY = "Veřejný údaj";
        private static final String NEVEREJNY = "Neveřejný údaj";

        @Test
        void objectVsSubject_isADistinctSyncableDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .types(List.of("Třída", TOP)).build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .types(List.of("Třída", TSP)).build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNotNull(result.getObjectSubjectType());
            assertEquals("objekt", result.getObjectSubjectType().getLocalValue());
            assertEquals("subjekt", result.getObjectSubjectType().getPublishedValue());
        }

        @Test
        void sameRole_noObjectSubjectDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .types(List.of("Třída", TOP)).build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .types(List.of("Třída", TOP)).build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertNull(result.getObjectSubjectType());
        }

        @Test
        void neitherSideHasRoleMarker_noObjectSubjectDeviation() {
            // A VLASTNOST/VZTAH carries no objekt/subjekt marker — must never deviate here.
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .types(List.of("Vlastnost")).build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .types(List.of("Vlastnost")).build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertNull(result.getObjectSubjectType());
        }

        @Test
        void publicVsPrivate_isADistinctSyncableDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .types(List.of("Třída", VEREJNY)).build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .types(List.of("Třída", NEVEREJNY)).build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNotNull(result.getIsPublic());
            assertEquals(Boolean.TRUE, result.getIsPublic().getLocalValue());
            assertEquals(Boolean.FALSE, result.getIsPublic().getPublishedValue());
        }

        @Test
        void sameClassification_noPublicPrivateDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .types(List.of("Třída", NEVEREJNY)).build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .types(List.of("Třída", NEVEREJNY)).build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertNull(result.getIsPublic());
        }
    }

    @Nested
    class NameIsNotCompared {

        // An OFN concept's IRI is derived from its name, so an IRI-matched pair shares a name by
        // construction. A name difference is therefore never emitted — it could only be NKD stale-IRI
        // corruption, and syncing it would rewrite our IRI and break the twin match.

        @Test
        void differentNames_produceNoNameDeviation_andNoOverallDeviation() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .name(Map.of("cs", "Místní název"))
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .name(Map.of("cs", "Publikovaný název"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertNull(result.getName(), "name deviation must never be emitted");
            assertEquals(PublishedConceptDeviationModel.DeviationStatus.NO_DEVIATION, result.getStatus(),
                    "a name-only difference must not register as a deviation");
        }

        @Test
        void differentNameButRealDeviationElsewhere_omitsNameButKeepsTheOther() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .name(Map.of("cs", "Místní"))
                    .definition(Map.of("cs", "Místní definice"))
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .name(Map.of("cs", "Publikovaný"))
                    .definition(Map.of("cs", "Publikovaná definice"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNull(result.getName(), "name is still omitted even when other fields deviate");
            assertNotNull(result.getDefinition());
        }
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
            // definition stands in for the generic multilingual-map null handling; name is no longer compared.
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .definition(null)
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .definition(Map.of("cs", "Definice"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNotNull(result.getDefinition());
            assertTrue(result.getDefinition().isDifferent());
            assertNull(result.getDefinition().getLocalValue());
            assertEquals(Map.of("cs", "Definice"), result.getDefinition().getPublishedValue());
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
                    .definition(Map.of("cs", "Definice"))
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .definition(Map.of("en", "Definition"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNotNull(result.getDefinition());
            assertTrue(result.getDefinition().isDifferent());
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
                    .definition(Map.of("cs", "Definice", "en", "Definition"))
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .definition(Map.of("cs", "Definice"))
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
        void localFalsePpdf_vsNullPublishedPpdf_shouldNotDeviate() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .isPpdf(false)
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .isPpdf(null)
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.NO_DEVIATION, result.getStatus());
            assertNull(result.getIsPpdf());
        }

        @Test
        void localTruePpdf_vsNullPublishedPpdf_shouldDeviateAsFalse() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .isPpdf(true)
                    .build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .isPpdf(null)
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertEquals(PublishedConceptDeviationModel.DeviationStatus.HAS_DEVIATIONS, result.getStatus());
            assertNotNull(result.getIsPpdf());
            assertTrue(result.getIsPpdf().isDifferent());
            assertEquals(Boolean.TRUE, result.getIsPpdf().getLocalValue());
            assertEquals(Boolean.FALSE, result.getIsPpdf().getPublishedValue());
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

    @Nested
    class EliHostCanonicalization {

        private static final String CANONICAL =
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2013/357/2024-01-01/dokument/norma/cast_2/hlava_2/par_8";
        private static final String LEGACY =
                "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2013/357/2024-01-01/dokument/norma/cast_2/hlava_2/par_8";

        @Test
        void legacyVsCanonicalHost_isNotADeviation_definingLegalSource() {
            // We store canonical .gov.cz; NKD still publishes legacy .cz. Same ELI, must not deviate.
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .definingLegalSources(List.of(CANONICAL)).build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .definingLegalSources(List.of(LEGACY)).build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertNull(result.getDefiningLegalSources());
        }

        @Test
        void legacyVsCanonicalHost_isNotADeviation_privacyProvisions() {
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .privacyProvisions(List.of(CANONICAL)).build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .privacyProvisions(List.of(LEGACY)).build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertNull(result.getPrivacyProvisions());
        }

        @Test
        void malformedCombinedNkdValue_isNotADeviation() {
            // NKD's only legal source is a ';'-joined pair (malformed upstream) and local has none.
            // The junk is dropped from the comparison, so this must NOT deviate forever.
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .definingLegalSources(List.of()).build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .definingLegalSources(List.of(
                            "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2013/256/2022-09-01/dokument/norma/cast_1/par_2/pism_a"
                                    + ";https://www.e-sbirka.cz/eli/cz/sb/2013/256/2022-09-01/dokument/norma/cast_1/par_2/pism_b"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertNull(result.getDefiningLegalSources());
        }

        @Test
        void differentEliPath_stillDeviates() {
            // Canonicalization only collapses the host — a genuinely different ELI must still deviate.
            OntologyDetailModel.ConceptDetailModel local = minimalConcept()
                    .definingLegalSources(List.of(CANONICAL)).build();
            OntologyDetailModel.ConceptDetailModel published = minimalConcept()
                    .definingLegalSources(List.of(
                            "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2013/357/2024-01-01/dokument/norma/cast_2/hlava_2/par_9"))
                    .build();

            PublishedConceptDeviationModel result = comparator.compareConceptDetails(local, published);

            assertNotNull(result.getDefiningLegalSources());
        }
    }
}
