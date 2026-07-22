package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A field one side models as an empty collection and the other as null must not deviate.
 *
 * <p>The local extractor defaults several collection fields to {@code List.of()} while NKD leaves them
 * null. Treating null-vs-empty as a difference made every working copy report {@code HAS_DEVIATIONS}
 * permanently — on fields that are not syncable, so the user could never clear them.
 */
class DeviationEmptyVsNullTest {

    private final ConceptDeviationComparator comparator = new ConceptDeviationComparator();

    /**
     * The regression. Non-legal sources are {@code List.of()} from the local extractor
     * ({@code OntologyDetailExtractor.buildNonLegalSources} returns empty, never null) and are never
     * populated on the NKD side.
     */
    @Test
    void nonLegalSources_localEmptyList_publishedNull_isNotADeviation() {
        ConceptDetailModel local = ConceptDetailModel.builder()
                .name(Map.of("cs", "Obec"))
                .definingNonLegalSources(List.of())
                .relatedNonLegalSources(List.of())
                .build();
        ConceptDetailModel published = ConceptDetailModel.builder()
                .name(Map.of("cs", "Obec"))
                .build();

        PublishedConceptDeviationModel deviation = comparator.compareConceptDetails(local, published);

        assertNull(deviation.getDefiningNonLegalSources(),
                "an empty local list vs a null published value is not a deviation");
        assertNull(deviation.getRelatedNonLegalSources());
        assertEquals(DeviationStatus.NO_DEVIATION, deviation.getStatus(),
                "identical concepts must not report deviations because of empty-vs-null");
    }

    @Test
    void emptyVsNull_isNotADeviation_forListAndMapFields() {
        ConceptDetailModel local = ConceptDetailModel.builder()
                .privacyProvisions(List.of())
                .sharingMethods(List.of())
                .alternativeName(Map.of())
                .build();
        ConceptDetailModel published = ConceptDetailModel.builder().build();

        PublishedConceptDeviationModel deviation = comparator.compareConceptDetails(local, published);

        assertNull(deviation.getPrivacyProvisions());
        assertNull(deviation.getSharingMethods());
        assertNull(deviation.getAlternativeName());
        assertEquals(DeviationStatus.NO_DEVIATION, deviation.getStatus());
    }

    /** The guard must not swallow real differences — populated vs empty still deviates. */
    @Test
    void populatedVsEmpty_isStillADeviation() {
        ConceptDetailModel local = ConceptDetailModel.builder()
                .privacyProvisions(List.of("§ 1"))
                .build();
        ConceptDetailModel published = ConceptDetailModel.builder()
                .privacyProvisions(List.of())
                .build();

        PublishedConceptDeviationModel deviation = comparator.compareConceptDetails(local, published);

        assertEquals(DeviationStatus.HAS_DEVIATIONS, deviation.getStatus(),
                "a real difference must survive the empty/null guard");
        assertNotNull(deviation.getPrivacyProvisions(),
                "the deviating field itself must be reported, not just the status");
        assertEquals(List.of("§ 1"), deviation.getPrivacyProvisions().getLocalValue());
    }

    @Test
    void populatedVsNull_isStillADeviation() {
        ConceptDetailModel local = ConceptDetailModel.builder()
                .privacyProvisions(List.of("§ 1"))
                .build();
        ConceptDetailModel published = ConceptDetailModel.builder().build();

        assertEquals(DeviationStatus.HAS_DEVIATIONS,
                comparator.compareConceptDetails(local, published).getStatus());
    }
}