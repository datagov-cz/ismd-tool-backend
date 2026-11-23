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
        service = new ConceptServiceImpl(createOperation, editOperation, deleteOperation, syncOperation, detailReader,
                new ConceptWriteTransaction(org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class)));
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
        com.dia.ismdtoolbackend.service.snapshot.NkdLookup nkd = org.mockito.Mockito.mock(
                com.dia.ismdtoolbackend.service.snapshot.NkdLookup.class);
        when(createOperation.prefetchNkd(createModel)).thenReturn(nkd);
        when(createOperation.createConcept(createModel, USER_ID, nkd)).thenReturn(created);

        assertThat(service.createConcept(createModel, USER_ID)).isSameAs(created);
        InOrder order = inOrder(createOperation);
        order.verify(createOperation).prefetchNkd(createModel);
        order.verify(createOperation).createConcept(createModel, USER_ID, nkd);
    }

    @Test
    void createConceptsCreatesEachInOrder() {
        ClassConceptModel first = ConceptModels.trida("První");
        ClassConceptModel second = ConceptModels.trida("Druhý");
        ConceptMetadataModel firstCreated = metadata("a");
        ConceptMetadataModel secondCreated = metadata("b");
        var firstNkd = org.mockito.Mockito.mock(com.dia.ismdtoolbackend.service.snapshot.NkdLookup.class);
        var secondNkd = org.mockito.Mockito.mock(com.dia.ismdtoolbackend.service.snapshot.NkdLookup.class);
        when(createOperation.prefetchNkd(first)).thenReturn(firstNkd);
        when(createOperation.prefetchNkd(second)).thenReturn(secondNkd);
        when(createOperation.createConcept(first, USER_ID, firstNkd)).thenReturn(firstCreated);
        when(createOperation.createConcept(second, USER_ID, secondNkd)).thenReturn(secondCreated);

        List<ConceptMetadataModel> result = service.createConcepts(List.of(first, second), USER_ID);

        assertThat(result).containsExactly(firstCreated, secondCreated);
        InOrder order = inOrder(createOperation);
        order.verify(createOperation).prefetchNkd(first);
        order.verify(createOperation).prefetchNkd(second);
        order.verify(createOperation).createConcept(first, USER_ID, firstNkd);
        order.verify(createOperation).createConcept(second, USER_ID, secondNkd);
    }

    /** NKD is asked first, then the edit runs with what was fetched. */
    @Test
    void editConceptPrefetchesNkdThenEdits() {
        ClassConceptEditModel editModel = ConceptServiceTestData.editModel();
        ConceptMetadataModel edited = metadata("a");
        com.dia.ismdtoolbackend.service.snapshot.NkdLookup nkd =
                com.dia.ismdtoolbackend.service.snapshot.NkdLookup.none();
        when(editOperation.prefetchNkd(CONCEPT_ID, editModel)).thenReturn(nkd);
        when(editOperation.editConcept(CONCEPT_ID, editModel, nkd)).thenReturn(edited);

        assertThat(service.editConcept(CONCEPT_ID, editModel)).isSameAs(edited);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(editOperation);
        order.verify(editOperation).prefetchNkd(CONCEPT_ID, editModel);
        order.verify(editOperation).editConcept(CONCEPT_ID, editModel, nkd);
    }

    @Test
    void deleteConceptDelegates() {
        service.deleteConcept(CONCEPT_ID);

        verify(deleteOperation).deleteConcept(CONCEPT_ID);
    }

    /** The plan (all NKD reads) first, then the write with it. */
    @Test
    void syncWorkingCopyPlansThenApplies() {
        List<String> fields = List.of("name");
        WorkingCopySyncOperation.SyncPlan plan =
                new WorkingCopySyncOperation.SyncPlan(null, "synced", null, false, 1, 1, null);
        org.springframework.transaction.PlatformTransactionManager txManager =
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        ConceptServiceImpl transactional = new ConceptServiceImpl(createOperation, editOperation, deleteOperation,
                syncOperation, detailReader, new ConceptWriteTransaction(txManager));
        when(syncOperation.planSync(CONCEPT_ID, fields)).thenReturn(plan);
        when(syncOperation.applySync(CONCEPT_ID, plan)).thenReturn("synced");

        assertThat(transactional.syncWorkingCopy(CONCEPT_ID, fields)).isEqualTo("synced");
        InOrder order = inOrder(syncOperation, txManager);
        order.verify(syncOperation).planSync(CONCEPT_ID, fields);
        order.verify(txManager).getTransaction(org.mockito.ArgumentMatchers.any());
        order.verify(syncOperation).applySync(CONCEPT_ID, plan);
    }

    @Test
    void getConceptDetailDelegates() {
        GetConceptDto detail = new GetConceptDto();
        when(detailReader.getConceptDetail(SLUG)).thenReturn(detail);

        assertThat(service.getConceptDetail(SLUG)).isSameAs(detail);
    }
}
