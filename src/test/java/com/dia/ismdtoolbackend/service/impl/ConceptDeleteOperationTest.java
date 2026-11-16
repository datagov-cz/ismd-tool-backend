package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import org.apache.jena.ontology.OntologyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.CONCEPT_ID;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.CONCEPT_IRI;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.GRAPH_NAME;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConceptDeleteOperationTest {

    private ConceptMetadataRepository conceptMetadataRepository;
    private JenaTDB2Repository jenaTDB2Repository;
    private OutboxWriter outboxWriter;
    private OutboxRelayTrigger outboxRelayTrigger;
    private ConceptDeleteOperation operation;

    private ConceptMetadataEntity concept;

    @BeforeEach
    void setUp() {
        ConceptServiceFixture fixture = new ConceptServiceFixture();
        conceptMetadataRepository = fixture.conceptMetadataRepository();
        jenaTDB2Repository = fixture.jenaTDB2Repository();
        outboxWriter = fixture.outboxWriter();
        outboxRelayTrigger = fixture.outboxRelayTrigger();
        operation = fixture.buildDeleteOperation();

        concept = ConceptServiceTestData.concept();
    }

    /** The concept exists and nothing in its graph points at it. */
    private void givenConceptWithoutRelated() {
        when(conceptMetadataRepository.findWithLockById(CONCEPT_ID)).thenReturn(Optional.of(concept));
        when(jenaTDB2Repository.findRelatedConceptUris(CONCEPT_IRI, GRAPH_NAME)).thenReturn(new ArrayList<>());
    }

    @Test
    void enqueuesTheDeleteAndRemovesTheRowWithoutRequiringRdf() {
        givenConceptWithoutRelated();
        when(conceptMetadataRepository.findByConceptIri(CONCEPT_IRI)).thenReturn(Optional.of(concept));

        operation.deleteConcept(CONCEPT_ID);

        verify(outboxWriter).enqueueDeleteConcepts(GRAPH_NAME, CONCEPT_IRI, List.of(CONCEPT_IRI));
        verify(conceptMetadataRepository).deleteAll(List.of(concept));
        verify(jenaTDB2Repository, never()).graphHasData(anyString());
        verify(jenaTDB2Repository, never()).conceptNotFoundInGraph(anyString(), anyString());
    }

    @Test
    void enqueuesInsteadOfDeletingFromTdb2Directly() {
        givenConceptWithoutRelated();

        operation.deleteConcept(CONCEPT_ID);

        verify(outboxWriter).enqueueDeleteConcepts(eq(GRAPH_NAME), eq(CONCEPT_IRI), anyList());
        verify(conceptMetadataRepository).deleteAll(anyList());
        verify(outboxRelayTrigger).nudgeAfterCommit();
        verify(jenaTDB2Repository, never()).deleteConceptsFromGraph(anyList(), anyString());
    }

    /** The row lock serializes concurrent edits and deletes of one concept. */
    @Test
    void takesThePessimisticRowLock() {
        givenConceptWithoutRelated();

        operation.deleteConcept(CONCEPT_ID);

        verify(conceptMetadataRepository).findWithLockById(CONCEPT_ID);
        verify(conceptMetadataRepository, never()).findById(CONCEPT_ID);
    }

    @Test
    void rejectsAnUnknownConcept() {
        when(conceptMetadataRepository.findWithLockById(CONCEPT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> operation.deleteConcept(CONCEPT_ID))
                .isInstanceOf(OntologyException.class)
                .hasMessageContaining("nebyla nalezena");
        verify(jenaTDB2Repository, never()).deleteConceptsFromGraph(anyList(), anyString());
        verify(conceptMetadataRepository, never()).deleteAll(any());
    }
}
