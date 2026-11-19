package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.testsupport.fixtures.ConceptModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.CONCEPT_ID;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.SLUG;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The orchestrator routes each operation to its delegate; the delegates have their own tests. */
@ExtendWith(MockitoExtension.class)
class ConceptServiceImplTest {

    @Mock private ConceptCreateOperation createOperation;
    @Mock private ConceptEditOperation editOperation;
    @Mock private ConceptDeleteOperation deleteOperation;
    @Mock private WorkingCopySyncOperation syncOperation;
    @Mock private ConceptDetailReader detailReader;

    private ConceptServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConceptServiceImpl(createOperation, editOperation, deleteOperation, syncOperation, detailReader);
    }

    private static ConceptMetadataModel metadata(String iri) {
        ConceptMetadataModel model = new ConceptMetadataModel();
        model.setConceptIri(iri);
        return model;
    }

    @Test
    void createConceptDelegates() {
        ClassConceptModel createModel = ConceptServiceTestData.createModel();
        ConceptMetadataModel created = metadata("a");
        when(createOperation.createConcept(createModel, USER_ID)).thenReturn(created);

        assertThat(service.createConcept(createModel, USER_ID)).isSameAs(created);
    }

    @Test
    void createConceptsCreatesEachInOrder() {
        ClassConceptModel first = ConceptModels.trida("První");
        ClassConceptModel second = ConceptModels.trida("Druhý");
        ConceptMetadataModel firstCreated = metadata("a");
        ConceptMetadataModel secondCreated = metadata("b");
        when(createOperation.createConcept(first, USER_ID)).thenReturn(firstCreated);
        when(createOperation.createConcept(second, USER_ID)).thenReturn(secondCreated);

        List<ConceptMetadataModel> result = service.createConcepts(List.of(first, second), USER_ID);

        assertThat(result).containsExactly(firstCreated, secondCreated);
        InOrder order = inOrder(createOperation);
        order.verify(createOperation).createConcept(first, USER_ID);
        order.verify(createOperation).createConcept(second, USER_ID);
    }

    @Test
    void editConceptDelegates() {
        ClassConceptEditModel editModel = ConceptServiceTestData.editModel();
        ConceptMetadataModel edited = metadata("a");
        when(editOperation.editConcept(CONCEPT_ID, editModel)).thenReturn(edited);

        assertThat(service.editConcept(CONCEPT_ID, editModel)).isSameAs(edited);
    }

    @Test
    void deleteConceptDelegates() {
        service.deleteConcept(CONCEPT_ID);

        verify(deleteOperation).deleteConcept(CONCEPT_ID);
    }

    @Test
    void syncWorkingCopyDelegates() {
        List<String> fields = List.of("name");
        when(syncOperation.syncWorkingCopy(CONCEPT_ID, fields)).thenReturn("synced");

        assertThat(service.syncWorkingCopy(CONCEPT_ID, fields)).isEqualTo("synced");
    }

    @Test
    void getAllDelegates() {
        List<ConceptMetadataModel> all = List.of(metadata("a"));
        when(detailReader.getAll(USER_ID, true)).thenReturn(all);

        assertThat(service.getAll(USER_ID, true)).isSameAs(all);
    }

    @Test
    void getConceptDetailDelegates() {
        GetConceptDto detail = new GetConceptDto();
        when(detailReader.getConceptDetail(SLUG)).thenReturn(detail);

        assertThat(service.getConceptDetail(SLUG)).isSameAs(detail);
    }
}
