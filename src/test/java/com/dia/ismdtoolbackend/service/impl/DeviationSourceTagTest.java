package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Phase D: one deviation model serves both cases, so it must say which case it is holding and what it was
 * compared against — otherwise the FE cannot tell whether {@code localValue} is a stored copy or the
 * user's own value, and cannot navigate to the NKD source.
 */
class DeviationSourceTagTest {

    private static final String NKD_IRI = "https://slovník.gov.cz/agendový/104/pojem/adresa";

    private final ConceptDeviationComparator comparator = new ConceptDeviationComparator();

    private ConceptDetailModel concept(String name) {
        return ConceptDetailModel.builder().iri(NKD_IRI).name(Map.of("cs", name)).build();
    }

    private ConceptDetailModel conceptWithDefinition(String name, String definition) {
        return ConceptDetailModel.builder().iri(NKD_IRI)
                .name(Map.of("cs", name))
                .definition(Map.of("cs", definition))
                .build();
    }

    @Test
    void workingCopy_stampsOriginAndSource() {
        PublishedConceptDeviationModel deviation = comparator.compareConceptDetails(
                concept("Můj název"), concept("Publikovaný název"), SnapshotOrigin.WORKING_COPY, NKD_IRI);

        assertEquals(SnapshotOrigin.WORKING_COPY, deviation.getOrigin());
        assertNotNull(deviation.getSource());
        assertEquals(NKD_IRI, deviation.getSource().getIri());
        assertEquals("Publikovaný název", deviation.getSource().getLabel(),
                "label comes from the NKD side, not the local one");
    }

    @Test
    void linkTarget_stampsOriginAndSource() {
        PublishedConceptDeviationModel deviation = comparator.compareConceptDetails(
                concept("Uložená kopie"), concept("Živé NKD"), SnapshotOrigin.LINK_TARGET, NKD_IRI);

        assertEquals(SnapshotOrigin.LINK_TARGET, deviation.getOrigin());
        assertEquals(NKD_IRI, deviation.getSource().getIri());
        assertEquals("Živé NKD", deviation.getSource().getLabel());
    }

    @Test
    void stamping_doesNotDisturbTheFieldDiff() {
        // The per-field diffs must stay byte-identical to the untagged call — only the envelope gains
        // fields, so the FE's existing field readers keep working. Name is not a compared field, so the
        // diff is driven by definition here.
        ConceptDetailModel local = conceptWithDefinition("Můj název", "Místní definice");
        ConceptDetailModel published = conceptWithDefinition("Publikovaný název", "Publikovaná definice");

        PublishedConceptDeviationModel untagged = comparator.compareConceptDetails(local, published);
        PublishedConceptDeviationModel tagged = comparator.compareConceptDetails(
                local, published, SnapshotOrigin.WORKING_COPY, NKD_IRI);

        assertEquals(untagged.getStatus(), tagged.getStatus());
        assertEquals(untagged.getDefinition().getLocalValue(), tagged.getDefinition().getLocalValue());
        assertEquals(untagged.getDefinition().getPublishedValue(), tagged.getDefinition().getPublishedValue());
    }

    @Test
    void unnamedNkdConcept_sourceLabelIsNull_iriStillPresent() {
        // The FE falls back to the IRI when the NKD concept carries no name.
        ConceptDetailModel unnamed = ConceptDetailModel.builder().iri(NKD_IRI).build();

        PublishedConceptDeviationModel deviation = comparator.compareConceptDetails(
                concept("Můj název"), unnamed, SnapshotOrigin.WORKING_COPY, NKD_IRI);

        assertNull(deviation.getSource().getLabel());
        assertEquals(NKD_IRI, deviation.getSource().getIri());
    }
}
