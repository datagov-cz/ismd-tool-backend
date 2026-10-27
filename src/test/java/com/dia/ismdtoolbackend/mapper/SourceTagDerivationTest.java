package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptSourceTag;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B2: {@code sourceTag} is derived from {@code is_published} in the mappers, so every {@code toDto}
 * caller ({@code getAll}, {@code getConceptDetail}, ontology detail) gets it without opting in. Exercises
 * the real MapStruct-generated impls.
 */
class SourceTagDerivationTest {

    private final ConceptMetadataMapper conceptMapper = new ConceptMetadataMapperImpl();
    private final OntologyMetadataMapper ontologyMapper = new OntologyMetadataMapperImpl();

    private ConceptMetadataEntity concept(Boolean isPublished) {
        ConceptMetadataEntity e = new ConceptMetadataEntity();
        e.setConceptIri("https://slovník.gov.cz/agendový/104/pojem/adresa");
        e.setIsPublished(isPublished);
        return e;
    }

    private OntologyMetadataEntity ontology(Boolean isPublished) {
        OntologyMetadataEntity e = new OntologyMetadataEntity();
        e.setGraphName("https://slovník.gov.cz/agendový/104");
        e.setIsPublished(isPublished);
        return e;
    }

    @Test
    void concept_published_isWorkingCopy() {
        ConceptMetadataModel model = conceptMapper.toDto(concept(true));

        assertThat(model.getSourceTag()).isEqualTo(ConceptSourceTag.WORKING_COPY);
        assertThat(model.getIsPublished()).as("isPublished stays for back-compat").isTrue();
    }

    @Test
    void concept_notPublished_isDraft() {
        assertThat(conceptMapper.toDto(concept(false)).getSourceTag()).isEqualTo(ConceptSourceTag.DRAFT);
    }

    @Test
    void concept_nullPublished_tagOmitted() {
        // "not recorded" is not the same claim as "confirmed local" — NON_NULL omits it from the payload
        // rather than asserting DRAFT.
        assertThat(conceptMapper.toDto(concept(null)).getSourceTag()).isNull();
    }

    @Test
    void ontology_published_isWorkingCopy() {
        OntologyMetadataModel model = ontologyMapper.toDto(ontology(true));

        assertThat(model.getSourceTag()).isEqualTo(ConceptSourceTag.WORKING_COPY);
        assertThat(model.getIsPublished()).isTrue();
    }

    @Test
    void ontology_notPublished_isDraft() {
        assertThat(ontologyMapper.toDto(ontology(false)).getSourceTag()).isEqualTo(ConceptSourceTag.DRAFT);
    }

    @Test
    void ontology_nullPublished_tagOmitted() {
        assertThat(ontologyMapper.toDto(ontology(null)).getSourceTag()).isNull();
    }

    @Test
    void workingCopyOntology_mayHoldDraftConcepts() {
        // The tag describes its own IRI, never its contents: severing one concept (Phase C) leaves the
        // ontology a working copy while that concept reads DRAFT. This mix is valid, not a desync.
        OntologyMetadataModel ontologyModel = ontologyMapper.toDto(ontology(true));
        ConceptMetadataModel severedConcept = conceptMapper.toDto(concept(false));

        assertThat(ontologyModel.getSourceTag()).isEqualTo(ConceptSourceTag.WORKING_COPY);
        assertThat(severedConcept.getSourceTag()).isEqualTo(ConceptSourceTag.DRAFT);
    }
}
